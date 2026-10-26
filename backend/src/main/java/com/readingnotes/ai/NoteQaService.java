package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.ai.ChromaClient.SearchHit;
import com.readingnotes.ai.CrossQaService.CrossHit;
import com.readingnotes.ai.dto.NoteQaStreamRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于笔记的问答（F3.4，API 文档 §7.6）
 *
 * 检索范围三选一（scope）：
 *  - book_only        仅原书（book_{book_id} 集合）
 *  - notes_only       仅笔记（notes_all 集合，按该书的 note_id 列表 where 过滤）
 *  - book_and_notes   原书 + 笔记（默认）
 *
 * 与跨书问答（F3.3）的差异：
 *  - 目标是"当前书 + 该书笔记"（F3.3 是"多本书 + 全局笔记"）
 *  - 会话 scope = note:{book_id}
 *  - citation 标注"原书第 X 页"或"我的笔记《XXX》"（笔记 metadata 无 page）
 *  - 拒答语义：book_only 无书内容拒答；notes_only 无笔记内容拒答；book_and_notes 合并后 Top-1 不足才拒答
 *  - 落库：父任务（QA, target=session）+ 子任务（书命中 1 条 target_type=book；
 *    笔记命中 1 条 target_type=note，target_id=首条命中笔记的 note_id）
 *
 * 说明：notes_all 集合 metadata 不含 book_id（团队约定：note_id/title/source_type/position/chunk_count），
 * 因此按书过滤笔记需先从 notes 表取出该书 note_id 列表，再用 Chroma where note_id $in 过滤。
 */
@Slf4j
@Service
public class NoteQaService {

    private static final String COLLECTION_PREFIX = "book_";
    private static final String NOTES_COLLECTION = "notes_all";

    private final EmbeddingProvider embedding;
    private final ChromaClient chroma;
    private final RagProperties ragProps;
    private final DoubaoClient doubao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NoteQaService(EmbeddingProvider embedding, ChromaClient chroma, RagProperties ragProps,
                         DoubaoClient doubao, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.embedding = embedding;
        this.chroma = chroma;
        this.ragProps = ragProps;
        this.doubao = doubao;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 笔记问答流式入口（异步线程执行，SSE 推送）
     */
    @Async("aiExecutor")
    public void streamNoteAnswer(NoteQaStreamRequest req, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        String traceId = TraceContext.current();
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            // 0. 参数合并（请求覆盖全局配置）
            String scope = req.normalizedScope();
            int topK = req.getTopK() != null ? req.getTopK() : ragProps.getRecallTopK();
            int rerankTopN = req.getRerankTopN() != null ? req.getRerankTopN() : ragProps.getRerankTopN();
            double threshold = req.getSimilarityThreshold() != null
                    ? req.getSimilarityThreshold() : ragProps.getSimilarityThreshold();
            double temperature = req.getTemperature() != null ? req.getTemperature() : 0.3;
            String bookId = req.getBookId().trim();

            // 1. 书是否存在且 READY
            Map<String, Object> book = jdbc.queryForMap(
                    "SELECT book_id, title FROM books WHERE book_id = ? AND status = 'READY' AND soft_delete = 0",
                    bookId);
            String bookTitle = book.get("title") == null ? bookId : String.valueOf(book.get("title"));

            // 2. 会话（scope=note:{bookId}）+ 多轮上下文 + 问题改写
            String sessionId = ensureSession(req, bookId);
            List<String> history = loadHistory(sessionId,
                    req.getMultiTurnN() != null ? req.getMultiTurnN() : ragProps.getMultiTurnN());
            String rewritten = rewriteQuestion(req.getQuestion(), history);

            // 3. 向量化一次，按 scope 召回
            List<Float> qVec = embedding.embed(List.of(rewritten)).get(0);

            List<CrossHit> all = new ArrayList<>();
            boolean bookIncluded = "book_only".equals(scope) || "book_and_notes".equals(scope);
            boolean notesIncluded = "notes_only".equals(scope) || "book_and_notes".equals(scope);

            if (bookIncluded) {
                try {
                    List<SearchHit> hits = chroma.query(COLLECTION_PREFIX + bookId, qVec, null, topK);
                    for (SearchHit h : hits) {
                        all.add(new CrossHit(h, bookId, bookTitle, "book", null, null));
                    }
                } catch (Exception e) {
                    log.warn("note qa retrieve book failed book={} trace={}", bookId, traceId, e);
                }
            }
            if (notesIncluded) {
                try {
                    // 该书的未删除笔记 note_id 列表（notes_all metadata 无 book_id，用 where $in 过滤）
                    List<Map<String, Object>> noteRows = jdbc.queryForList(
                            "SELECT note_id, title FROM notes WHERE source_book_id = ? AND soft_delete = 0",
                            bookId);
                    if (!noteRows.isEmpty()) {
                        List<String> noteIds = new ArrayList<>();
                        Map<String, String> noteTitles = new HashMap<>();
                        for (Map<String, Object> r : noteRows) {
                            String nid = String.valueOf(r.get("note_id"));
                            noteIds.add(nid);
                            noteTitles.put(nid, r.get("title") == null ? "" : String.valueOf(r.get("title")));
                        }
                        Map<String, Object> where = new LinkedHashMap<>();
                        where.put("note_id", Map.of("$in", noteIds));
                        List<SearchHit> hits = chroma.query(NOTES_COLLECTION, qVec, where, topK);
                        for (SearchHit h : hits) {
                            JsonNode meta = h.getMetadata();
                            String noteId = meta == null ? "" : meta.path("note_id").asText("");
                            String noteTitle = meta == null ? ""
                                    : meta.path("title").asText(noteTitles.getOrDefault(noteId, ""));
                            all.add(new CrossHit(h, null, null, "note", noteId, noteTitle));
                        }
                    } else if ("notes_only".equals(scope)) {
                        // 本书没有任何笔记，notes_only 无内容可查
                        sendDoneRefused(emitter, taskId, sessionId, threshold, 0.0, "本书暂无笔记，请先创建笔记并开启回流");
                        persistRefused(sessionId, rewritten);
                        return;
                    }
                } catch (BusinessException e) {
                    if ("notes_only".equals(scope)) {
                        sendDoneRefused(emitter, taskId, sessionId, threshold, 0.0, "笔记库不可用，请稍后重试");
                        persistRefused(sessionId, rewritten);
                        return;
                    }
                    log.warn("note qa retrieve notes failed book={} trace={}", bookId, traceId, e);
                } catch (Exception e) {
                    log.warn("note qa retrieve notes failed book={} trace={}", bookId, traceId, e);
                }
            }

            // 4. 全局排序（相似度降序）取 Top-N
            all.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
            List<CrossHit> top = all.subList(0, Math.min(all.size(), rerankTopN));

            // 5. meta 事件
            sendMeta(emitter, taskId, sessionId, top, rewritten, scope);

            // 6. 阈值兜底拒答（不调 LLM）
            double top1 = top.isEmpty() ? 0.0 : scoreOf(top.get(0));
            if (top1 < threshold) {
                sendDoneRefused(emitter, taskId, sessionId, threshold, top1,
                        "book_only".equals(scope) ? "本书中未提及相关内容" : "所选书籍与笔记中未提及相关内容");
                persistRefused(sessionId, rewritten);
                return;
            }

            // 7. 每片段发 citation 事件（标注"原书第X页"/"我的笔记《XXX》"）
            for (CrossHit ch : top) {
                sendCitation(emitter, ch);
            }

            // 8. 笔记问答 Prompt（区分原书/笔记来源）
            List<DoubaoClient.ChatMessage> messages = buildNotePrompt(top, rewritten, bookTitle);

            // 9. 流式调用 + SSE 推送
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

            // 10. 落库 + done
            String versionId = persistAnswer(sessionId, rewritten, answer.toString(), top, bookId, scope);
            long latency = System.currentTimeMillis() - start;
            sendDone(emitter, taskId, sessionId, latency, versionId, answer.length());
            log.info("note qa done session={} book={} scope={} latency={}ms trace={}",
                    sessionId, bookId, scope, latency, traceId);

        } catch (BusinessException e) {
            sendError(emitter, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("note qa stream failed trace={}", traceId, e);
            sendError(emitter, ErrorCode.AI_UNAVAILABLE.getCode(), "AI 服务暂时不可用，请重试");
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 会话与上下文 ====================

    private String ensureSession(NoteQaStreamRequest req, String bookId) {
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
                VALUES (?, ?, ?, NULL, ?, ?)
                """, sessionId, "note:" + bookId, bookId, now, now);
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

    /** 与 F3.2/F3.3 相同的指代补全：仅含指代/追问线索时才拼接最近一轮问题 */
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

    // ==================== Prompt 构建（F3.4：区分原书/笔记来源） ====================

    private List<DoubaoClient.ChatMessage> buildNotePrompt(List<CrossHit> hits, String question, String bookTitle) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            CrossHit h = hits.get(i);
            if ("book".equals(h.getSourceType())) {
                ctx.append("[《").append(h.getBookTitle()).append("》第")
                        .append(pageOf(h)).append("页]：").append(h.getDocument()).append("\n");
            } else {
                ctx.append("[我的笔记《").append(h.getNoteTitle()).append("》]：")
                        .append(h.getDocument()).append("\n");
            }
        }
        String systemPrompt = "你是一个读书助手。请结合原书内容和用户自己的读书笔记回答用户问题。\n\n"
                + "【要求】\n"
                + "1. 区分来源：原书观点标注\"原书第X页\"，笔记内容标注\"我的笔记《XXX》\"\n"
                + "2. 如果笔记中有用户自己的思考，优先结合用户思考回答\n"
                + "3. 如果某来源没有相关内容，明确说明\n"
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

    private String persistAnswer(String sessionId, String question, String answer,
                                 List<CrossHit> hits, String bookId, String scope) {
        String now = Instant.now().toString();
        // user 消息
        String userMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', ?, ?)
                """, userMsgId, sessionId, question, now);

        // assistant 消息 + citations
        String asstMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String cited = citationsJson(hits);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, cited_chunks_json, created_at)
                VALUES (?, ?, 'assistant', ?, ?, ?)
                """, asstMsgId, sessionId, answer, cited, now);

        // 父任务（QA, target=session）
        String parentTaskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ObjectNode params = mapper.createObjectNode()
                .put("book_id", bookId)
                .put("scope", scope);
        try {
            jdbc.update("""
                    INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                          params_json, created_at, started_at, finished_at, trace_id)
                    VALUES (?, 'QA', 'session', ?, 'COMPLETED', ?, ?, ?, ?, ?)
                    """,
                    parentTaskId, sessionId, params.toString(), now, now, now, TraceContext.current());
        } catch (Exception e) {
            log.warn("persist note ai_task(parent) failed session={}", sessionId, e);
            return null;
        }

        // 子任务：书来源 1 条（target_type=book）；笔记来源 1 条（target_type=note，target_id=首条 note_id）
        boolean bookHit = hits.stream().anyMatch(h -> "book".equals(h.getSourceType()));
        boolean noteHit = hits.stream().anyMatch(h -> "note".equals(h.getSourceType()));
        long noteCount = hits.stream().filter(h -> "note".equals(h.getSourceType())).count();
        String firstNoteId = null;
        for (CrossHit h : hits) {
            if ("note".equals(h.getSourceType())) {
                firstNoteId = h.getNoteId();
                break;
            }
        }
        if (bookHit) {
            insertChild(parentTaskId, "book", bookId,
                    mapper.createObjectNode().put("book_id", bookId).toString(), now);
        }
        if (noteHit && firstNoteId != null) {
            ObjectNode np = mapper.createObjectNode()
                    .put("note_count", noteCount)
                    .put("note_id", firstNoteId);
            insertChild(parentTaskId, "note", firstNoteId, np.toString(), now);
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
                    mapper.createObjectNode().put("scope", scope).toString(),
                    now);
            return versionId;
        } catch (Exception e) {
            log.warn("persist note ai_version failed session={}", sessionId, e);
            return null;
        }
    }

    /** 子任务 INSERT（target_type 仅 book/note，受 ai_tasks CHECK 约束） */
    private void insertChild(String parentTaskId, String targetType, String targetId,
                             String paramsJson, String now) {
        try {
            String childTaskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            jdbc.update("""
                    INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                          params_json, parent_task_id, created_at, started_at, finished_at, trace_id)
                    VALUES (?, 'QA', ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?)
                    """,
                    childTaskId, targetType, targetId, paramsJson,
                    parentTaskId, now, now, now, TraceContext.current());
        } catch (Exception e) {
            log.warn("persist note child task failed type={} target={}", targetType, targetId, e);
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
                          List<CrossHit> hits, String rewritten, String scope) {
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
                            .put("scope", scope)
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
                                 double threshold, double top1Score, String message) {
        try {
            emitter.send(SseEmitter.event().name("done")
                    .data(mapper.createObjectNode()
                            .put("task_id", taskId)
                            .put("session_id", sessionId)
                            .put("refused", true)
                            .put("message", message)
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
}
