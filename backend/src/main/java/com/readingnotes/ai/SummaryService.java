package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.ai.ChromaClient.SearchHit;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 全书摘要（F3.1，API 文档 §7.1/7.2/7.3）
 *
 * map-reduce 分层（需求 §F3.1）：
 *  - Map：按章节（chapter_path 元数据）分组，每组采样关键片段（章首/章尾/节首段），
 *         每章生成"章节小结"（每个章节小结 = 一个子任务，parent_task_id 指向主任务）
 *  - Reduce：所有章节小结 + 全书开头/结尾汇总，生成最终摘要
 *  - 章节过多时自动二级分层：每 8 个章节小结打包一层，逐层归并到 1 份
 *
 * 版本与状态：每次生成写 ai_versions（artifact_type=SUMMARY，version_number+1，is_active=1）；
 * 任务状态落 ai_tasks（task_type=SUMMARY），查询走 GET /ai/tasks/{task_id}（progress_hint 从子任务统计）。
 *
 * 数据源：Chroma 集合 book_{book_id}（chunks 表可能为空——构建只写向量库）。
 */
@Slf4j
@Service
public class SummaryService {

    private static final String COLLECTION_PREFIX = "book_";
    /** 章节过少时不分层（≤1 章直接 reduce） */
    private static final int MAP_GROUP_LIMIT = 8;
    /** 章节小结 LLM 并发路数：2 路并行（防豆包限流），24 章摘要从 ~90s 降到 ~45s */
    private static final int MAP_CONCURRENCY = 2;

    private final ChromaClient chroma;
    private final DoubaoClient doubao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SummaryService(ChromaClient chroma, DoubaoClient doubao, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.chroma = chroma;
        this.doubao = doubao;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ==================== 提交 ====================

    /**
     * 提交摘要任务（202 异步）。已有 QUEUED/RUNNING 同书任务 → 40004（幂等，§14.2）。
     *
     * @return 主任务 task_id
     */
    public String submitSummary(String bookId, Double temperature, String detailLevel) {
        bookId = bookId == null ? "" : bookId.trim();
        if (bookId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "book_id 不能为空");
        }
        ensureBookReady(bookId);

        Integer running = jdbc.queryForObject("""
                SELECT COUNT(1) FROM ai_tasks
                WHERE task_type='SUMMARY' AND target_type='book' AND target_id=?
                  AND status IN ('QUEUED','RUNNING')
                """, Integer.class, bookId);
        if (running != null && running > 0) {
            throw new BusinessException(ErrorCode.AI_TASK_RUNNING);
        }

        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String now = Instant.now().toString();
        ObjectNode params = mapper.createObjectNode()
                .put("temperature", temperature == null ? 0.3 : temperature)
                .put("detail_level", normalizeDetailLevel(detailLevel));
        jdbc.update("""
                INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                      params_json, created_at, trace_id)
                VALUES (?, 'SUMMARY', 'book', ?, 'QUEUED', ?, ?, ?)
                """, taskId, bookId, params.toString(), now, TraceContext.current());
        log.info("summary task submitted task={} book={} trace={}", taskId, bookId, TraceContext.current());
        return taskId;
    }

    private void ensureBookReady(String bookId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT book_id, title FROM books WHERE book_id = ? AND status='READY' AND soft_delete=0", bookId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
    }

    private String normalizeDetailLevel(String level) {
        if (level == null) {
            return "standard";
        }
        String s = level.trim().toLowerCase();
        if (s.equals("brief") || s.equals("standard") || s.equals("detailed")) {
            return s;
        }
        return "standard";
    }

    // ==================== 异步执行（map-reduce） ====================

    /**
     * 执行摘要任务：Map（每章子任务生成章节小结）→ 分层归并 → Reduce（最终摘要）。
     */
    @Async("aiExecutor")
    public void runSummary(String taskId, String bookId, double temperature, String detailLevel) {
        String now = Instant.now().toString();
        String traceId = TraceContext.current();
        try {
            // 1. 主任务 RUNNING
            jdbc.update("UPDATE ai_tasks SET status='RUNNING', started_at=? WHERE task_id=?", now, taskId);

            // 2. 读全书切片（Chroma 集合），按章节分组
            long total = chroma.count(COLLECTION_PREFIX + bookId);
            if (total <= 0) {
                throw new BusinessException(ErrorCode.BUILD_FAILED, "该书籍知识库为空，无法生成摘要");
            }
            List<SearchHit> all = chroma.getAll(COLLECTION_PREFIX + bookId, (int) Math.min(total, 5000));
            Map<String, List<SearchHit>> byChapter = groupByChapter(all);
            List<String> chapterKeys = new ArrayList<>(byChapter.keySet());

            // 3. Map：章节小结 LLM 调用并发（最多 2 路同时，防豆包限流；原串行 24 章约 90s，
            //    并发后大幅缩短）。子任务落库与引用收集仍按章节原顺序执行，保证进度统计稳定。
            ExecutorService mapExec = Executors.newFixedThreadPool(MAP_CONCURRENCY, r -> {
                Thread t = new Thread(r, "summary-map");
                t.setDaemon(true);
                return t;
            });
            List<Future<String>> futures = new ArrayList<>();
            try {
                for (String chapter : chapterKeys) {
                    final String ch = chapter;
                    futures.add(mapExec.submit(() -> {
                        String sample = sampleChapter(byChapter.get(ch));
                        String s = doubao.chat(
                                List.of(new DoubaoClient.ChatMessage("user",
                                        "你是图书整理助手。请把下面章节的内容压缩成一段不超过 150 字的章节小结，"
                                                + "保留关键观点与结论，直接输出小结文本，不要加标题。\n\n"
                                                + "【章节】" + ch + "\n【章节内容】\n" + sample)),
                                0.3, 400);
                        return s == null ? "" : s.trim();
                    }));
                }
            } finally {
                mapExec.shutdown();
            }

            List<String> chapterSummaries = new ArrayList<>();
            // 引用溯源（深层缺陷⑮）：记录每个来源章节名与页码范围，落 citations_json
            ObjectNode citations = mapper.createObjectNode();
            ArrayNode cited = mapper.createArrayNode();
            int done = 0;
            for (int i = 0; i < chapterKeys.size(); i++) {
                String chapter = chapterKeys.get(i);
                String childId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                ObjectNode cp = mapper.createObjectNode().put("chapter", chapter);
                jdbc.update("""
                        INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                              params_json, parent_task_id, created_at, started_at, trace_id)
                        VALUES (?, 'SUMMARY', 'book', ?, 'RUNNING', ?, ?, ?, ?, ?)
                        """, childId, bookId, cp.toString(), taskId, now, now, traceId);

                Set<Integer> chapterPages = new TreeSet<>();
                for (SearchHit h : byChapter.get(chapter)) {
                    int p = h.getMetadata().path("page").asInt(0);
                    if (p > 0) {
                        chapterPages.add(p);
                    }
                }
                ObjectNode c = mapper.createObjectNode().put("chapter", chapter);
                ArrayNode pages = mapper.createArrayNode();
                chapterPages.forEach(pages::add);
                c.set("pages", pages);
                cited.add(c);

                String summary;
                try {
                    summary = futures.get(i).get(40, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("summary map task failed chapter={} err={}", chapter, e.getMessage());
                    summary = "";
                }
                chapterSummaries.add("【" + chapter + "】" + summary);

                ObjectNode cr = mapper.createObjectNode().put("chapter_summary", summary);
                jdbc.update("UPDATE ai_tasks SET status='COMPLETED', result_json=?, finished_at=? WHERE task_id=?",
                        cr.toString(), Instant.now().toString(), childId);
                done++;
            }

            // 4. 章节过多时分批归并（二级 Map）
            while (chapterSummaries.size() > 1) {
                if (chapterSummaries.size() <= MAP_GROUP_LIMIT) {
                    break;
                }
                List<String> merged = new ArrayList<>();
                for (int i = 0; i < chapterSummaries.size(); i += MAP_GROUP_LIMIT) {
                    List<String> group = chapterSummaries.subList(i, Math.min(i + MAP_GROUP_LIMIT, chapterSummaries.size()));
                    String m = doubao.chat(
                            List.of(new DoubaoClient.ChatMessage("user",
                                    "请把下面若干章节小结合并成一份连贯的归纳文本，不要遗漏关键点，直接输出：\n\n"
                                            + String.join("\n", group))),
                            0.3, 800);
                    merged.add(m == null ? "" : m.trim());
                }
                chapterSummaries = merged;
            }

            // 5. Reduce：章节小结 + 全书开头/结尾 → 最终摘要
            String opening = sampleOpening(all);
            String finalSummary = doubao.chat(
                    List.of(new DoubaoClient.ChatMessage("user",
                            "你是一个图书摘要助手。请根据全书开头结尾与各章节小结，生成《" + bookId + "》的完整摘要。\n"
                                    + "要求：总分结构；先一句话概括全书主题，再分章节列出要点；"
                                    + lengthHint(detailLevel) + "直接输出 Markdown 摘要正文。\n\n"
                                    + "【全书开头/结尾】\n" + opening + "\n\n【章节小结】\n"
                                    + String.join("\n", chapterSummaries))),
                    temperature, 1600);
            if (finalSummary == null || finalSummary.isBlank()) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            }

            // 6. 落库：ai_versions（SUMMARY 版本递增，仅一条激活）+ 主任务 COMPLETED
            citations.set("chapters", cited);
            String citationsJson = mapper.writeValueAsString(citations);
            persistVersion(taskId, bookId, finalSummary, detailLevel, temperature,
                    citationsJson, promptVersionRef("summary_reduce"));
            jdbc.update("""
                    UPDATE ai_tasks SET status='COMPLETED', result_json=?, finished_at=?, error_message=NULL
                    WHERE task_id=?
                    """, mapper.createObjectNode().put("summary_length", finalSummary.length()).toString(),
                    Instant.now().toString(), taskId);
            log.info("summary done task={} book={} chapters={} trace={}", taskId, bookId, chapterKeys.size(), traceId);

        } catch (BusinessException e) {
            jdbc.update("UPDATE ai_tasks SET status='FAILED', error_message=?, finished_at=? WHERE task_id=?",
                    e.getMessage(), Instant.now().toString(), taskId);
            log.warn("summary failed task={} trace={}", taskId, traceId, e);
        } catch (Exception e) {
            jdbc.update("UPDATE ai_tasks SET status='FAILED', error_message=?, finished_at=? WHERE task_id=?",
                    e.toString(), Instant.now().toString(), taskId);
            log.error("summary task failed trace={}", traceId, e);
        }
    }

    private String lengthHint(String detailLevel) {
        return switch (detailLevel == null ? "standard" : detailLevel) {
            case "brief" -> "摘要控制在 300 字以内；";
            case "detailed" -> "摘要控制在 1200 字以内，尽量详尽；";
            default -> "摘要控制在 600 字以内；";
        };
    }

    /** 按 chapter_path 元数据分组（保留章节顺序，空 path 归入 "未分章节"） */
    private Map<String, List<SearchHit>> groupByChapter(List<SearchHit> all) {
        Map<String, List<SearchHit>> map = new LinkedHashMap<>();
        for (SearchHit h : all) {
            JsonNode meta = h.getMetadata();
            String chapter = meta == null ? "" : meta.path("chapter_path").asText("");
            if (chapter == null || chapter.isBlank()) {
                chapter = "未分章节";
            }
            map.computeIfAbsent(chapter, k -> new ArrayList<>()).add(h);
        }
        for (List<SearchHit> list : map.values()) {
            list.sort(Comparator.comparingInt(this::pageOf)
                    .thenComparing(h -> h.getId() == null ? "" : h.getId()));
        }
        return map;
    }

    /** 章节采样：章首 + 章尾 + 中间（最多 4 段） */
    private String sampleChapter(List<SearchHit> chapterHits) {
        StringBuilder sb = new StringBuilder();
        if (chapterHits.size() <= 4) {
            for (SearchHit h : chapterHits) {
                sb.append("- ").append(h.getDocument()).append("\n");
            }
            return sb.toString();
        }
        sb.append("【章首】").append(chapterHits.get(0).getDocument()).append("\n");
        sb.append("【章尾】").append(chapterHits.get(chapterHits.size() - 1).getDocument()).append("\n");
        for (int i = 1; i < chapterHits.size() - 1 && i < 3; i++) {
            sb.append("【节选】").append(chapterHits.get(i).getDocument()).append("\n");
        }
        return sb.toString();
    }

    /** 全书开头/结尾采样（最多各 2 段） */
    private String sampleOpening(List<SearchHit> all) {
        StringBuilder sb = new StringBuilder();
        int n = all.size();
        int head = Math.min(2, n);
        for (int i = 0; i < head; i++) {
            sb.append("【开头】").append(all.get(i).getDocument()).append("\n");
        }
        for (int i = Math.max(head, n - 2); i < n; i++) {
            sb.append("【结尾】").append(all.get(i).getDocument()).append("\n");
        }
        return sb.toString();
    }

    private int pageOf(SearchHit h) {
        JsonNode meta = h.getMetadata();
        if (meta != null) {
            return meta.path("page").asInt(0);
        }
        return 0;
    }

    // ==================== 版本与查询 ====================

    /**
     * 当前激活的 Prompt 版本引用（深层缺陷⑭）：prompt_versions 以 (name, version) 定位，
     * 引用键为 {@code name#version}（如 summary_reduce#1）；无匹配返回 null（不阻断生成）。
     */
    private String promptVersionRef(String name) {
        try {
            return jdbc.queryForObject("""
                    SELECT CONCAT(name, '#', version) FROM prompt_versions
                     WHERE is_active = 1 AND name = ? LIMIT 1
                    """, String.class, name);
        } catch (Exception e) {
            return null;
        }
    }

    private void persistVersion(String taskId, String bookId, String content,
                                String detailLevel, double temperature,
                                String citationsJson, String promptVersion) {
        String now = Instant.now().toString();
        jdbc.update("UPDATE ai_versions SET is_active=0 "
                + "WHERE target_type='book' AND target_id=? AND artifact_type='SUMMARY'", bookId);
        Integer maxVer = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number),0) FROM ai_versions "
                        + "WHERE target_type='book' AND target_id=? AND artifact_type='SUMMARY'",
                Integer.class, bookId);
        int versionNumber = (maxVer == null ? 0 : maxVer) + 1;
        String versionId = "ver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ObjectNode params = mapper.createObjectNode()
                .put("detail_level", normalizeDetailLevel(detailLevel))
                .put("temperature", temperature);
        jdbc.update("""
                INSERT INTO ai_versions
                    (version_id, task_id, target_type, target_id, artifact_type, version_number,
                     is_active, content, citations_json, prompt_version_id, params_snapshot_json, created_at)
                VALUES (?, ?, 'book', ?, 'SUMMARY', ?, 1, ?, ?, ?, ?, ?)
                """, versionId, taskId, bookId, versionNumber, content,
                citationsJson == null ? "{}" : citationsJson, promptVersion, params.toString(), now);
    }

    /**
     * 获取摘要（API 文档 §7.2）。
     *
     * @param version "latest"（默认）或具体版本号
     */
    public Map<String, Object> getSummary(String bookId, String version) {
        bookId = bookId == null ? "" : bookId.trim();
        if (bookId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "book_id 不能为空");
        }
        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT version_id, version_number, is_active, created_at, content
                FROM ai_versions
                WHERE target_type='book' AND target_id=? AND artifact_type='SUMMARY'
                ORDER BY version_number DESC
                """, bookId);
        if (versions.isEmpty()) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND, "该书暂无摘要，请先生成");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", bookId);
        List<Object> versionList = new ArrayList<>();
        Map<String, Object> active = null;
        Map<String, Object> target = null;
        for (Map<String, Object> v : versions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version_id", v.get("version_id"));
            item.put("version_number", v.get("version_number"));
            item.put("is_active", ((Number) v.get("is_active")).intValue() == 1);
            item.put("created_at", v.get("created_at"));
            versionList.add(item);
            if (((Number) v.get("is_active")).intValue() == 1) {
                active = v;
            }
        }
        data.put("current_version", active == null ? versions.get(0).get("version_number") : active.get("version_number"));
        data.put("versions", versionList);

        if ("latest".equalsIgnoreCase(version) || version == null || version.isBlank()) {
            target = active != null ? active : versions.get(0);
        } else {
            for (Map<String, Object> v : versions) {
                if (String.valueOf(v.get("version_number")).equals(version)) {
                    target = v;
                    break;
                }
            }
            if (target == null) {
                throw new BusinessException(ErrorCode.VERSION_NOT_FOUND);
            }
        }
        data.put("content", target.get("content") == null ? "" : String.valueOf(target.get("content")));
        return data;
    }

    // ==================== 任务状态（§7.12，通用轮询） ====================

    /**
     * 任务状态查询：ai_tasks 行 + progress_hint（摘要类任务从子任务统计"已生成 X/N"）。
     */
    public Map<String, Object> getTask(String taskId) {
        taskId = taskId == null ? "" : taskId.trim();
        if (taskId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "task_id 不能为空");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT task_id, task_type, target_type, target_id, status, retry_count,
                       params_json, result_json, error_message, created_at, started_at, finished_at, trace_id
                FROM ai_tasks WHERE task_id=?
                """, taskId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", r.get("task_id"));
        data.put("task_type", r.get("task_type"));
        data.put("status", r.get("status"));
        data.put("retry_count", r.get("retry_count"));
        data.put("params_json", r.get("params_json"));
        data.put("result_json", r.get("result_json"));
        data.put("error_message", r.get("error_message"));
        data.put("created_at", r.get("created_at"));
        data.put("started_at", r.get("started_at"));
        data.put("finished_at", r.get("finished_at"));
        data.put("trace_id", r.get("trace_id"));

        // progress_hint：子任务完成数 / 子任务总数（map-reduce 进度）
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM ai_tasks WHERE parent_task_id=?", Integer.class, taskId);
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(1) FROM ai_tasks WHERE parent_task_id=? AND status='COMPLETED'",
                Integer.class, taskId);
        if (total != null && total > 0) {
            int d = done == null ? 0 : done;
            data.put("progress_hint", "已生成 " + d + "/" + total + " 章小结");
        }
        return data;
    }

    // ==================== 事件/JSON 工具 ====================

    /** 供控制器输出：submitted task 结构（§7.1 Response 202） */
    public Map<String, Object> taskResult(String taskId, String bookId) {
        Map<String, Object> t = getTask(taskId);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("task_id", taskId);
        n.put("book_id", bookId);
        n.put("status", t.get("status"));
        n.put("trace_id", t.get("trace_id"));
        return n;
    }
}
