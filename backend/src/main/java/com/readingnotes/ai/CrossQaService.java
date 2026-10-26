package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.ai.ChromaClient.SearchHit;
import com.readingnotes.ai.dto.CrossQaStreamRequest;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.TraceContext;
import com.readingnotes.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跨书问答（F3.3，API 文档 §7.5）
 *
 * 与书本内问答（F3.2）的差异：
 * - 检索范围：多本书集合（book_{id}）+ 可选 notes_all（全局笔记集合）
 * - 每书独立召回后全局按相似度排序，再取 Top-N（文档：全局 rerank 后取 Top-8）
 * - 拒答为全局判定：所有来源合并后的 Top-1 仍低于阈值才拒答
 *   （某本书没有相关内容不算拒答，Prompt 会明确说明该来源无内容）
 * - SSE 增加 citation 事件（source_type=book/note，标注书名+页码 / 笔记标题）
 * - 落库：ai_tasks 记父任务 + 每书子任务（parent_task_id）
 *
 * 安全：api-key 只走后端；异常统一转友好中文（§6.2）。
 */
@Slf4j
@Service
public class CrossQaService {

    private static final String COLLECTION_PREFIX = "book_";
    private static final String NOTES_COLLECTION = "notes_all";

    private final EmbeddingProvider embedding;
    private final ChromaClient chroma;
    private final RagProperties ragProps;
    private final DoubaoClient doubao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CrossQaService(EmbeddingProvider embedding, ChromaClient chroma, RagProperties ragProps,
                          DoubaoClient doubao, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.embedding = embedding;
        this.chroma = chroma;
        this.ragProps = ragProps;
        this.doubao = doubao;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 跨书流式问答入口（异步线程执行，SSE 推送）
     */
    @Async("aiExecutor")
    public void streamCrossAnswer(CrossQaStreamRequest req, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        String traceId = TraceContext.current();
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            // 0. 参数合并（请求覆盖全局配置）
            int topK = req.getTopK() != null ? req.getTopK() : ragProps.getRecallTopK();
            int rerankTopN = req.getRerankTopN() != null ? req.getRerankTopN() : ragProps.getRerankTopN();
            double threshold = req.getSimilarityThreshold() != null
                    ? req.getSimilarityThreshold() : ragProps.getSimilarityThreshold();
            boolean includeNotes = req.getIncludeNotes() == null || req.getIncludeNotes();
            double temperature = req.getTemperature() != null ? req.getTemperature() : 0.3;

            // 1. 解析参与书籍：显式指定或全部 READY（空且无 READY → 40005）
            List<String> bookIds = new ArrayList<>();
            Map<String, String> bookTitles = new HashMap<>();
            if (req.getBookIds() == null || req.getBookIds().isEmpty()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT book_id, title FROM books WHERE status = 'READY' AND soft_delete = 0 "
                                + "ORDER BY created_at DESC");
                for (Map<String, Object> r : rows) {
                    bookIds.add(String.valueOf(r.get("book_id")));
                    bookTitles.put(String.valueOf(r.get("book_id")),
                            r.get("title") == null ? "" : String.valueOf(r.get("title")));
                }
            } else {
                for (String id : req.getBookIds()) {
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    bookIds.add(id);
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT book_id, title FROM books WHERE book_id = ? AND status = 'READY' AND soft_delete = 0",
                            id);
                    if (!rows.isEmpty()) {
                        bookTitles.put(id,
                                rows.get(0).get("title") == null ? "" : String.valueOf(rows.get(0).get("title")));
                    } else {
                        bookTitles.put(id, id);
                    }
                }
            }
            if (bookIds.isEmpty()) {
                throw new BusinessException(ErrorCode.CROSS_SCOPE_EMPTY);
            }

            // 2. 会话（scope=cross）+ 多轮上下文 + 问题改写
            String sessionId = ensureSession(req);
            List<String> history = loadHistory(sessionId,
                    req.getMultiTurnN() != null ? req.getMultiTurnN() : ragProps.getMultiTurnN());
            String rewritten = rewriteQuestion(req.getQuestion(), history);

            // 3. 向量化一次，逐集合召回
            List<Float> qVec = embedding.embed(List.of(rewritten)).get(0);

            // 4. 每书召回（各自保留书信息）+ 可选 notes_all
            List<CrossHit> all = new ArrayList<>();
            for (String bookId : bookIds) {
                try {
                    List<SearchHit> hits = chroma.query(COLLECTION_PREFIX + bookId, qVec, null, topK);
                    for (SearchHit h : hits) {
                        all.add(new CrossHit(h, bookId, bookTitles.getOrDefault(bookId, bookId),
                                "book", null, null));
                    }
                } catch (Exception e) {
                    log.warn("cross retrieve failed collection=book_{} trace={}", bookId, traceId, e);
                }
            }
            if (includeNotes) {
                try {
                    List<SearchHit> hits = chroma.query(NOTES_COLLECTION, qVec, null, topK);
                    for (SearchHit h : hits) {
                        JsonNode meta = h.getMetadata();
                        String noteId = meta == null ? "" : meta.path("note_id").asText("");
                        String noteTitle = meta == null ? "" : meta.path("title").asText("");
                        all.add(new CrossHit(h, null, null, "note", noteId, noteTitle));
                    }
                } catch (Exception e) {
                    log.warn("cross retrieve notes_all failed trace={}", traceId, e);
                }
            }

            // 5. 全局排序（相似度降序）取 Top-N
            all.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
            List<CrossHit> top = all.subList(0, Math.min(all.size(), rerankTopN));

            // 6. meta 事件（chunk 带来源信息）
            sendMeta(emitter, taskId, sessionId, top, rewritten);

            // 7. 全局阈值兜底：Top-1 仍不足 → 拒答（不调 LLM）
            double top1 = top.isEmpty() ? 0.0 : scoreOf(top.get(0));
            if (top1 < threshold) {
                sendDoneRefused(emitter, taskId, sessionId, threshold, top1);
                persistRefused(sessionId, rewritten);
                return;
            }

            // 8. 每片段发 citation 事件（文档 §7.5：标注书名+页码 / 笔记标题）
            for (CrossHit ch : top) {
                sendCitation(emitter, ch);
            }

            // 9. 跨书 Prompt（按来源分别陈述，标注书名+页码 / 笔记标题）
            List<DoubaoClient.ChatMessage> messages = buildCrossPrompt(top, rewritten);

            // 10. 流式调用 + SSE 推送
            StringBuilder answer = new StringBuilder();
            doubao.chatStream(messages, temperature, 1024, delta -> {
                try {
                    emitter.send(SseEmitter.event().name("delta")
                            .data(mapper.createObjectNode().put("content", delta)));
                    answer.append(delta);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 11. 落库（chat_messages + ai_tasks 父/子 + ai_versions）+ done
            String versionId = persistAnswer(sessionId, rewritten, answer.toString(), top);
            long latency = System.currentTimeMillis() - start;
            sendDone(emitter, taskId, sessionId, latency, versionId, answer.length());
            log.info("cross qa done session={} books={} latency={}ms trace={}",
                    sessionId, bookIds.size(), latency, traceId);

        } catch (BusinessException e) {
            sendError(emitter, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("cross qa stream failed trace={}", traceId, e);
            sendError(emitter, ErrorCode.AI_UNAVAILABLE.getCode(), "AI 服务暂时不可用，请重试");
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 会话与上下文 ====================

    private String ensureSession(CrossQaStreamRequest req) {
        String sessionId = req.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            jdbc.update("UPDATE chat_sessions SET updated_at = ? WHERE session_id = ?",
                    Instant.now().toString(), sessionId);
            return sessionId;
        }
        sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO chat_sessions (session_id, scope, book_id, title, created_at, updated_at)
                VALUES (?, 'cross', NULL, NULL, ?, ?)
                """, sessionId, now, now);
        return sessionId;
    }

    private List<String> loadHistory(String sessionId, int multiTurnN) {
        if (multiTurnN <= 0) {
            return List.of();
        }
        List<String> latest = jdbc.query("""
                        SELECT role, content FROM chat_messages
                        WHERE session_id = ? ORDER BY created_at DESC, message_id DESC LIMIT ?
                        """,
                (rs, i) -> rs.getString("role") + ": " + rs.getString("content"),
                sessionId, multiTurnN * 2);
        Collections.reverse(latest);
        return latest;
    }

    /** 与 F3.2 相同的指代补全：仅含指代/追问线索时才拼接最近一轮问题 */
    private String rewriteQuestion(String question, List<String> history) {
        if (history.isEmpty()) {
            return question;
        }
        boolean hasReference = question.matches(".*(它|那个|这个|上面|刚才|上一条|前一句|继续说|然后呢|具体|详细).*");
        if (!hasReference) {
            return question;
        }
        String lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String msg = history.get(i);
            if (msg.startsWith("user: ")) {
                lastUser = msg.substring(6);
                break;
            }
        }
        if (lastUser == null) {
            return question;
        }
        return "结合我上一个问题【" + lastUser + "】，请回答我当前的问题：" + question;
    }

    // ==================== Prompt 构建（§5.3 跨书模板） ====================

    private List<DoubaoClient.ChatMessage> buildCrossPrompt(List<CrossHit> hits, String question) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            CrossHit h = hits.get(i);
            ctx.append("[《").append(h.getBookTitle()).append("》第")
                    .append(pageOf(h)).append("页]：").append(h.getDocument()).append("\n");
        }
        String systemPrompt = "你是一个读书助手。以下是来自多本书籍和笔记的片段，请综合回答用户问题。\n\n"
                + "【要求】\n"
                + "1. 按来源分别陈述不同书的观点，对比异同\n"
                + "2. 每个观点标注来源（书名+页码 / 笔记标题）\n"
                + "3. 如果某本书没有相关内容，明确说明\n"
                + "4. 不要引入片段之外的信息";
        String userPrompt = "【片段列表】\n" + ctx + "\n【用户问题】" + question;
        return List.of(
                new DoubaoClient.ChatMessage("system", systemPrompt),
                new DoubaoClient.ChatMessage("user", userPrompt));
    }

    private int pageOf(CrossHit h) {
        JsonNode meta = h.getMetadata();
        if (meta != null) {
            return meta.path("page").asInt(0);
        }
        return 0;
    }

    private double scoreOf(CrossHit h) {
        return Math.max(0.0, 1.0 - h.getDistance());
    }

    // ==================== 落库 ====================

    private void persistRefused(String sessionId, String question) {
        String now = Instant.now().toString();
        String userMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', ?, ?)
                """, userMsgId, sessionId, question, now);
    }

    private String persistAnswer(String sessionId, String question, String answer, List<CrossHit> hits) {
        String now = Instant.now().toString();
        // user 消息
        String userMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', ?, ?)
                """, userMsgId, sessionId, question, now);

        // assistant 消息 + citations（含来源）
        String asstMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String cited = citationsJson(hits);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, cited_chunks_json, created_at)
                VALUES (?, ?, 'assistant', ?, ?, ?)
                """, asstMsgId, sessionId, answer, cited, now);

        // 父任务（QA, target=session）
        String parentTaskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ObjectNode params = mapper.createObjectNode();
        List<String> bookIds = new ArrayList<>();
        for (CrossHit h : hits) {
            if ("book".equals(h.getSourceType()) && !bookIds.contains(h.getBookId())) {
                bookIds.add(h.getBookId());
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode arr = params.putArray("book_ids");
        for (String bookId : bookIds) {
            arr.add(bookId);
        }
        try {
            jdbc.update("""
                    INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                          params_json, created_at, started_at, finished_at, trace_id)
                    VALUES (?, 'QA', 'session', ?, 'COMPLETED', ?, ?, ?, ?, ?)
                    """,
                    parentTaskId, sessionId, params.toString(), now, now, now, TraceContext.current());
        } catch (Exception e) {
            log.warn("persist cross ai_task(parent) failed session={}", sessionId, e);
            return null;
        }

        // 子任务：每本参与的书各记一条（parent_task_id 指向父任务；target_type 用 'book'，
        // 受 ai_tasks 表 CHECK 约束限制：仅 book/note/session/dataset）
        for (String bookId : bookIds) {
            try {
                String childTaskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                jdbc.update("""
                        INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                              params_json, parent_task_id, created_at, started_at, finished_at, trace_id)
                        VALUES (?, 'QA', 'book', ?, 'COMPLETED', ?, ?, ?, ?, ?, ?)
                        """,
                        childTaskId, bookId,
                        mapper.createObjectNode().put("book_id", bookId).toString(),
                        parentTaskId, now, now, now, TraceContext.current());
            } catch (Exception e) {
                log.warn("persist cross child task failed book={}, err={}", bookId, e.toString(), e);
            }
        }

        // ai_versions：同会话 ANSWER 版本递增，仅一条激活
        try {
            jdbc.update("UPDATE ai_versions SET is_active = 0 "
                    + "WHERE target_type = 'session' AND target_id = ? AND artifact_type = 'ANSWER'",
                    sessionId);
            Integer maxVer = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version_number), 0) FROM ai_versions "
                            + "WHERE target_type = 'session' AND target_id = ? AND artifact_type = 'ANSWER'",
                    Integer.class, sessionId);
            int versionNumber = (maxVer == null ? 0 : maxVer) + 1;
            String versionId = "ver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            jdbc.update("""
                    INSERT INTO ai_versions
                        (version_id, task_id, target_type, target_id, artifact_type, version_number,
                         is_active, content, citations_json, params_snapshot_json, created_at)
                    VALUES (?, ?, 'session', ?, 'ANSWER', ?, 1, ?, ?, ?, ?)
                    """,
                    versionId, parentTaskId, sessionId, versionNumber,
                    answer, cited,
                    mapper.createObjectNode().put("include_notes", true).toString(),
                    now);
            return versionId;
        } catch (Exception e) {
            log.warn("persist cross ai_version failed session={}", sessionId, e);
            return null;
        }
    }

    private String citationsJson(List<CrossHit> hits) {
        try {
            List<Object> arr = new ArrayList<>();
            for (CrossHit h : hits) {
                ObjectNode n = mapper.createObjectNode()
                        .put("chunk_id", h.getId())
                        .put("source_type", h.getSourceType())
                        .put("snippet", h.getDocument().length() > 100
                                ? h.getDocument().substring(0, 100) : h.getDocument());
                if ("book".equals(h.getSourceType())) {
                    n.put("book_id", h.getBookId()).put("book_title", h.getBookTitle())
                            .put("page", pageOf(h));
                } else {
                    n.put("note_id", h.getNoteId() == null ? "" : h.getNoteId())
                            .put("note_title", h.getNoteTitle() == null ? "" : h.getNoteTitle());
                }
                arr.add(n);
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    // ==================== SSE 事件推送 ====================

    private void sendMeta(SseEmitter emitter, String taskId, String sessionId,
                          List<CrossHit> hits, String rewritten) {
        try {
            List<Object> chunks = new ArrayList<>();
            for (CrossHit h : hits) {
                ObjectNode n = mapper.createObjectNode()
                        .put("chunk_id", h.getId())
                        .put("source_type", h.getSourceType())
                        .put("score", scoreOf(h));
                if ("book".equals(h.getSourceType())) {
                    n.put("book_id", h.getBookId()).put("book_title", h.getBookTitle())
                            .put("page", pageOf(h));
                } else {
                    n.put("note_id", h.getNoteId() == null ? "" : h.getNoteId())
                            .put("note_title", h.getNoteTitle() == null ? "" : h.getNoteTitle());
                }
                chunks.add(n);
            }
            emitter.send(SseEmitter.event().name("meta")
                    .data(mapper.createObjectNode()
                            .put("task_id", taskId)
                            .put("session_id", sessionId)
                            .put("rewritten_question", rewritten)
                            .set("retrieved_chunks", mapper.valueToTree(chunks))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendCitation(SseEmitter emitter, CrossHit h) {
        try {
            ObjectNode n = mapper.createObjectNode()
                    .put("source_type", h.getSourceType())
                    .put("snippet", h.getDocument().length() > 220
                            ? h.getDocument().substring(0, 220) : h.getDocument());
            if ("book".equals(h.getSourceType())) {
                n.put("book_id", h.getBookId()).put("book_title", h.getBookTitle())
                        .put("page", pageOf(h));
            } else {
                n.put("note_id", h.getNoteId() == null ? "" : h.getNoteId())
                        .put("note_title", h.getNoteTitle() == null ? "" : h.getNoteTitle());
            }
            emitter.send(SseEmitter.event().name("citation").data(n));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDone(SseEmitter emitter, String taskId, String sessionId,
                          long latency, String versionId, int answerLength) {
        try {
            emitter.send(SseEmitter.event().name("done")
                    .data(mapper.createObjectNode()
                            .put("message_id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                            .put("session_id", sessionId)
                            .put("version_id", versionId == null ? "" : versionId)
                            .put("latency_ms", latency)
                            .put("refused", false)
                            .put("answer_length", answerLength)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDoneRefused(SseEmitter emitter, String taskId, String sessionId,
                                 double threshold, double top1Score) {
        try {
            emitter.send(SseEmitter.event().name("done")
                    .data(mapper.createObjectNode()
                            .put("task_id", taskId)
                            .put("session_id", sessionId)
                            .put("refused", true)
                            .put("message", "所选书籍与笔记中未提及相关内容")
                            .put("threshold", threshold)
                            .put("top1_score", top1Score)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendError(SseEmitter emitter, int code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(mapper.createObjectNode()
                            .put("code", code)
                            .put("message", message)));
        } catch (Exception ignored) {
        }
    }

    // ==================== 内部模型 ====================

    /** 检索片段 + 来源信息（book: book_id/book_title；note: note_id/note_title） */
    public static class CrossHit {
        private final SearchHit hit;
        private final String bookId;
        private final String bookTitle;
        private final String sourceType;
        private final String noteId;
        private final String noteTitle;

        public CrossHit(SearchHit hit, String bookId, String bookTitle, String sourceType,
                        String noteId, String noteTitle) {
            this.hit = hit;
            this.bookId = bookId;
            this.bookTitle = bookTitle;
            this.sourceType = sourceType;
            this.noteId = noteId;
            this.noteTitle = noteTitle;
        }

        public String getId() {
            return hit.getId();
        }

        public String getDocument() {
            return hit.getDocument();
        }

        public JsonNode getMetadata() {
            return hit.getMetadata();
        }

        public double getDistance() {
            return hit.getDistance();
        }

        public String getBookId() {
            return bookId;
        }

        public String getBookTitle() {
            return bookTitle == null || bookTitle.isBlank() ? "未命名书籍" : bookTitle;
        }

        public String getSourceType() {
            return sourceType;
        }

        public String getNoteId() {
            return noteId;
        }

        public String getNoteTitle() {
            return noteTitle == null || noteTitle.isBlank() ? "未命名笔记" : noteTitle;
        }
    }
}
