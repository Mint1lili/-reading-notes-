package com.readingnotes.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.ai.RagPipeline.RetrieveResult;
import com.readingnotes.ai.dto.QaStreamRequest;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 书本内问答（F3.2，API 文档 §7.4）
 *
 * 全链路：
 * 1. 多轮上下文：取会话最近 N 轮（multi-turn-n，默认 3）
 * 2. 问题改写：指代补全（"那它呢？" → 结合上一轮）
 * 3. 检索：RagPipeline（查询理解→元数据过滤→向量召回 TopK→阈值兜底）
 * 4. 拒答：Top-1 低于阈值 → 直接 done(refused=true)，不调 LLM
 * 5. 拼 Prompt：召回片段原文 + 改写后问题，约束"仅依据片段回答"
 * 6. 流式：调豆包 chatStream，每段 delta 推送 SSE
 * 7. 落库：chat_messages（user+assistant）+ ai_versions（ANSWER）
 *
 * 安全：api-key 只走后端，SSE 事件不含 key；异常统一转友好中文（§6.2）
 */
@Slf4j
@Service
public class QaService {

    private static final String COLLECTION_PREFIX = "book_";

    private final RagPipeline rag;
    private final DoubaoClient doubao;
    private final RagProperties ragProps;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QaService(RagPipeline rag, DoubaoClient doubao, RagProperties ragProps,
                     JdbcTemplate jdbc, ObjectMapper mapper) {
        this.rag = rag;
        this.doubao = doubao;
        this.ragProps = ragProps;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 流式问答入口（异步线程执行，SSE 推送）
     */
    @Async("aiExecutor")
    public void streamAnswer(QaStreamRequest req, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        String traceId = TraceContext.current();
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            // 0. 参数合并（请求覆盖全局配置）
            int topK = req.getTopK() != null ? req.getTopK() : ragProps.getRecallTopK();
            int rerankTopN = req.getRerankTopN() != null ? req.getRerankTopN() : ragProps.getRerankTopN();
            double threshold = req.getSimilarityThreshold() != null
                    ? req.getSimilarityThreshold() : ragProps.getSimilarityThreshold();
            boolean enableMetaFilter = req.getEnableMetadataFilter() != null
                    ? req.getEnableMetadataFilter() : ragProps.isEnableMetadataFilter();
            double temperature = req.getTemperature() != null ? req.getTemperature() : 0.3;

            // 1. 会话：不存在则创建
            String sessionId = ensureSession(req);

            // 2. 多轮上下文 + 问题改写
            List<String> history = loadHistory(sessionId, req.getMultiTurnN() != null ? req.getMultiTurnN() : ragProps.getMultiTurnN());
            String rewritten = rewriteQuestion(req.getQuestion(), history);

            // 3. 检索（复用 RagPipeline 三层 RAG）
            RetrieveResult result = rag.retrieve("book_" + req.getBookId(), rewritten);

            // 4. meta 事件：告知前端已检索到 N 个片段
            List<com.readingnotes.ai.ChromaClient.SearchHit> hits = result.getHits();
            sendMeta(emitter, taskId, sessionId, hits, rewritten);

            // 5. 拒答兜底：低于阈值不调 LLM（比赛亮点：可现场演示拒答）
            if (result.isRefused()) {
                sendDoneRefused(emitter, taskId, sessionId, threshold, hits);
                persistQa(req.getBookId(), sessionId, rewritten, null, null, true);
                return;
            }

            // 6. 拼 Prompt（仅依据召回原文回答，禁止引入书外知识）
            List<com.readingnotes.ai.DoubaoClient.ChatMessage> messages = buildPrompt(hits, rewritten);

            // 7. 流式调用 + SSE 推送
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

            // 8. done：落库 + 推送完成事件
            String versionId = persistQa(req.getBookId(), sessionId, rewritten, answer.toString(), hits, false);
            long latency = System.currentTimeMillis() - start;
            sendDone(emitter, taskId, sessionId, answer.toString(), hits, latency, versionId);
            log.info("qa done session={} latency={}ms trace={}", sessionId, latency, traceId);

        } catch (BusinessException e) {
            sendError(emitter, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("qa stream failed trace={}", traceId, e);
            sendError(emitter, ErrorCode.AI_UNAVAILABLE.getCode(), "AI 服务暂时不可用，请重试");
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 会话与上下文 ====================

    private String ensureSession(QaStreamRequest req) {
        String sessionId = req.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            // 已存在则刷新 updated_at
            jdbc.update("UPDATE chat_sessions SET updated_at = ? WHERE session_id = ?",
                    Instant.now().toString(), sessionId);
            return sessionId;
        }
        sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO chat_sessions (session_id, scope, book_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, "book:" + req.getBookId(), req.getBookId(), null, now, now);
        return sessionId;
    }

    private List<String> loadHistory(String sessionId, int multiTurnN) {
        if (multiTurnN <= 0) {
            return List.of();
        }
        // 取最近 multiTurnN*2 条（user+assistant 成对）
        List<String> latest = jdbc.query("""
                        SELECT role, content FROM chat_messages
                        WHERE session_id = ? ORDER BY created_at DESC, message_id DESC LIMIT ?
                        """,
                (rs, i) -> rs.getString("role") + ": " + rs.getString("content"),
                sessionId, multiTurnN * 2);
        // 倒序为时间正序（JDK17 无 List.reversed()，手动反转）
        java.util.Collections.reverse(latest);
        return latest;
    }

    /**
     * 多轮指代补全（透明执行）。
     * 仅当问题含指代/追问线索（它、那、这、上面、刚才…）时才拼接最近一轮用户问题，
     * 避免把完全无关的新问题拽向历史主题（否则会破坏拒答判定）。
     */
    private String rewriteQuestion(String question, List<String> history) {
        if (history.isEmpty()) {
            return question;
        }
        // 指代/追问线索：含则需结合上下文；不含则原样提问
        // 注意不用单字"这/那"（"这本书"是泛指书籍，不是指代），只用明确指代/追问词
        boolean hasReference = question.matches(".*(它|那个|这个|上面|刚才|上一条|前一句|继续说|然后呢|具体|详细).*");
        if (!hasReference) {
            return question;
        }
        // 找最近一条 user 消息
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

    // ==================== Prompt 构建 ====================

    private List<com.readingnotes.ai.DoubaoClient.ChatMessage> buildPrompt(
            List<com.readingnotes.ai.ChromaClient.SearchHit> hits, String question) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            com.readingnotes.ai.ChromaClient.SearchHit h = hits.get(i);
            ctx.append("[片段").append(i + 1).append("]")
                    .append("(第").append(pageOf(h)).append("页)\n")
                    .append(h.getDocument()).append("\n\n");
        }
        String systemPrompt = "你是严格的书籍内容问答助手。"
                + "你只能依据下方提供的【书籍片段】回答用户问题，"
                + "禁止引入书籍之外的观点和知识；如果片段中没有相关内容，"
                + "直接说明\u201c本书未提及\u201d。回答时标注引用的页码。";
        String userPrompt = "【书籍片段】\n" + ctx + "【问题】" + question;
        return List.of(
                new com.readingnotes.ai.DoubaoClient.ChatMessage("system", systemPrompt),
                new com.readingnotes.ai.DoubaoClient.ChatMessage("user", userPrompt));
    }

    private int pageOf(com.readingnotes.ai.ChromaClient.SearchHit h) {
        if (h.getMetadata() != null) {
            return h.getMetadata().path("page").asInt(0);
        }
        return 0;
    }

    // ==================== 落库 ====================

    private String persistQa(String bookId, String sessionId, String question, String answer,
                             List<com.readingnotes.ai.ChromaClient.SearchHit> hits, boolean refused) {
        String now = Instant.now().toString();
        // user 消息
        String userMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', ?, ?)
                """, userMsgId, sessionId, question, now);

        if (refused) {
            return null;
        }
        // assistant 消息
        String asstMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String cited = "[]";
        try {
            if (hits != null && !hits.isEmpty()) {
                List<Object> arr = new ArrayList<>();
                for (com.readingnotes.ai.ChromaClient.SearchHit h : hits) {
                    arr.add(mapper.createObjectNode()
                            .put("chunk_id", h.getId())
                            .put("page", pageOf(h))
                            .put("snippet", h.getDocument().length() > 100
                                    ? h.getDocument().substring(0, 100) : h.getDocument()));
                }
                cited = mapper.writeValueAsString(arr);
            }
        } catch (Exception ignored) {
        }
        jdbc.update("""
                INSERT INTO chat_messages (message_id, session_id, role, content, cited_chunks_json, created_at)
                VALUES (?, ?, 'assistant', ?, ?, ?)
                """, asstMsgId, sessionId, answer, cited, now);

        // ai_tasks 记录问答任务（ai_versions.task_id 外键关联，必须先建任务）
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            jdbc.update("""
                    INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                          params_json, created_at, started_at, finished_at, trace_id)
                    VALUES (?, 'QA', 'session', ?, 'COMPLETED', ?, ?, ?, ?, ?)
                    """,
                    taskId, sessionId,
                    mapper.createObjectNode().put("book_id", bookId).toString(),
                    now, now, now, TraceContext.current());
        } catch (Exception e) {
            log.warn("persist ai_task failed session={}", sessionId, e);
            return null;
        }

        // ai_versions 存 ANSWER 产物（同一会话 ANSWER 版本号递增，仅一条激活）
        try {
            // 旧版本失活
            jdbc.update("UPDATE ai_versions SET is_active = 0 "
                    + "WHERE target_type = 'session' AND target_id = ? AND artifact_type = 'ANSWER'",
                    sessionId);
            // 版本号 = 已有最大 + 1
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
                    versionId, taskId, sessionId, versionNumber,
                    answer, cited,
                    mapper.createObjectNode().put("book_id", bookId).toString(),
                    now);
            return versionId;
        } catch (Exception e) {
            log.warn("persist ai_version failed session={}", sessionId, e);
            return null;
        }
    }

    // ==================== SSE 事件推送 ====================

    private void sendMeta(SseEmitter emitter, String taskId, String sessionId,
                          List<com.readingnotes.ai.ChromaClient.SearchHit> hits, String rewritten) {
        try {
            List<Object> chunks = new ArrayList<>();
            for (com.readingnotes.ai.ChromaClient.SearchHit h : hits) {
                chunks.add(mapper.createObjectNode()
                        .put("chunk_id", h.getId())
                        .put("page", pageOf(h))
                        .put("score", Math.max(0.0, 1.0 - h.getDistance())));
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

    private void sendDone(SseEmitter emitter, String taskId, String sessionId, String answer,
                          List<com.readingnotes.ai.ChromaClient.SearchHit> hits, long latency, String versionId) {
        try {
            emitter.send(SseEmitter.event().name("done")
                    .data(mapper.createObjectNode()
                            .put("message_id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                            .put("session_id", sessionId)
                            .put("version_id", versionId == null ? "" : versionId)
                            .put("latency_ms", latency)
                            .put("refused", false)
                            .put("answer_length", answer.length())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDoneRefused(SseEmitter emitter, String taskId, String sessionId, double threshold,
                                 List<com.readingnotes.ai.ChromaClient.SearchHit> hits) {
        try {
            double top1 = hits.isEmpty() ? 0.0 : Math.max(0.0, 1.0 - hits.get(0).getDistance());
            emitter.send(SseEmitter.event().name("done")
                    .data(mapper.createObjectNode()
                            .put("task_id", taskId)
                            .put("session_id", sessionId)
                            .put("refused", true)
                            .put("message", "本书中未提及相关内容")
                            .put("threshold", threshold)
                            .put("top1_score", top1)));
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
