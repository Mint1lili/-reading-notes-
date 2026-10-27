package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 思维导图（F3.5，API 文档 §7.7/7.8/7.9）
 *
 *  - 提交：POST /mindmap {book_id, source: summary|chapter|structure, source_version_id?, chapter_path?} → 202
 *  - 生成：source=summary 时基于该书 SUMMARY 产物（可指定版本，默认 latest）；
 *          source=chapter 时基于指定章节切片（Chroma 按 chapter_path 过滤）；
 *          source=structure 时按 chapters 表章节架构生成（骨架=目录标题，要点参考摘要）
 *  - 产物：Markdown 层级导图（# 根主题 / ## 一级分支 / ### 二级分支 / - 叶子要点），写 ai_versions(MINDMAP)
 *  - 编辑：PATCH /mindmap/{version_id} {content} 直接改内容，不递增版本号
 *
 * 版本/状态与摘要一致：task_type=MINDMAP 落 ai_tasks，轮询走 GET /ai/tasks/{task_id}。
 */
@Slf4j
@Service
public class MindmapService {

    private static final String COLLECTION_PREFIX = "book_";

    private final ChromaClient chroma;
    private final DoubaoClient doubao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MindmapService(ChromaClient chroma, DoubaoClient doubao, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.chroma = chroma;
        this.doubao = doubao;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ==================== 提交 ====================

    /**
     * 提交导图任务（202 异步）。已有 QUEUED/RUNNING 同书任务 → 40004（幂等，§14.2）。
     *
     * @return 主任务 task_id
     */
    public String submitMindmap(String bookId, String source, String sourceVersionId, String chapterPath) {
        bookId = bookId == null ? "" : bookId.trim();
        if (bookId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "book_id 不能为空");
        }
        String src = normalizeSource(source);
        if (src == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "source 仅支持 summary、chapter 或 structure");
        }
        if ("chapter".equals(src) && (chapterPath == null || chapterPath.isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "source=chapter 时必须提供 chapter_path");
        }
        ensureBookReady(bookId);
        if ("summary".equals(src)) {
            ensureSummaryExists(bookId, sourceVersionId);
        }

        Integer running = jdbc.queryForObject("""
                SELECT COUNT(1) FROM ai_tasks
                WHERE task_type='MINDMAP' AND target_type='book' AND target_id=?
                  AND status IN ('QUEUED','RUNNING')
                """, Integer.class, bookId);
        if (running != null && running > 0) {
            throw new BusinessException(ErrorCode.AI_TASK_RUNNING);
        }

        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ObjectNode params = mapper.createObjectNode()
                .put("source", src)
                .put("source_version_id", sourceVersionId == null ? "" : sourceVersionId)
                .put("chapter_path", chapterPath == null ? "" : chapterPath);
        jdbc.update("""
                INSERT INTO ai_tasks (task_id, task_type, target_type, target_id, status,
                                      params_json, created_at, trace_id)
                VALUES (?, 'MINDMAP', 'book', ?, 'QUEUED', ?, ?, ?)
                """, taskId, bookId, params.toString(), Instant.now().toString(), TraceContext.current());
        log.info("mindmap task submitted task={} book={} source={} trace={}",
                taskId, bookId, src, TraceContext.current());
        return taskId;
    }

    private void ensureBookReady(String bookId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT book_id FROM books WHERE book_id = ? AND status='READY' AND soft_delete=0", bookId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
    }

    private void ensureSummaryExists(String bookId, String sourceVersionId) {
        List<Map<String, Object>> rows;
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            rows = jdbc.queryForList(
                    "SELECT version_id FROM ai_versions WHERE target_type='book' AND target_id=?"
                            + " AND artifact_type='SUMMARY' AND version_id=?",
                    bookId, sourceVersionId);
        } else {
            rows = jdbc.queryForList(
                    "SELECT version_id FROM ai_versions WHERE target_type='book' AND target_id=?"
                            + " AND artifact_type='SUMMARY' ORDER BY version_number DESC LIMIT 1",
                    bookId);
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND, "该书暂无摘要，请先生成摘要再生成导图");
        }
    }

    private String normalizeSource(String source) {
        if (source == null) {
            return "summary";
        }
        String s = source.trim().toLowerCase();
        if (s.equals("summary") || s.equals("chapter") || s.equals("structure")) {
            return s;
        }
        return null;
    }

    // ==================== 异步执行 ====================

    @Async("aiExecutor")
    public void runMindmap(String taskId, String bookId, String source, String sourceVersionId, String chapterPath) {
        String now = Instant.now().toString();
        String traceId = TraceContext.current();
        try {
            jdbc.update("UPDATE ai_tasks SET status='RUNNING', started_at=? WHERE task_id=?", now, taskId);

            String material;
            String content;
            String prompt;
            if ("chapter".equals(source)) {
                material = loadChapter(bookId, chapterPath);
                prompt = "你是思维导图整理助手。请把下面内容整理成 Markdown 层级思维导图：\n"
                        + "以 # 开头写根主题（书名），## 写一级分支，### 写二级分支，"
                        + "更细的要点用 - 列表；层级清晰、要点完整，不要输出解释，直接输出 Markdown。\n\n"
                        + "【内容】\n" + material;
                content = doubao.chat(
                        List.of(new DoubaoClient.ChatMessage("user", prompt)), 0.3, 2000);
            } else if ("structure".equals(source)) {
                // 骨架严格等于书目录（按页序），要点由 LLM 单独提取后由后端拼接，
                // 章节标题与顺序 100% 可控，不与摘要顺序混排
                content = generateStructureMindmap(bookId, sourceVersionId);
            } else {
                material = loadSummary(bookId, sourceVersionId);
                prompt = "你是思维导图整理助手。请把下面内容整理成 Markdown 层级思维导图：\n"
                        + "以 # 开头写根主题（书名），## 写一级分支，### 写二级分支，"
                        + "更细的要点用 - 列表；层级清晰、要点完整，不要输出解释，直接输出 Markdown。\n\n"
                        + "【内容】\n" + material;
                content = doubao.chat(
                        List.of(new DoubaoClient.ChatMessage("user", prompt)), 0.3, 2000);
            }
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            }

            persistVersion(taskId, bookId, content, source, sourceVersionId, chapterPath);
            jdbc.update("""
                    UPDATE ai_tasks SET status='COMPLETED', result_json=?, finished_at=?, error_message=NULL
                    WHERE task_id=?
                    """, mapper.createObjectNode().put("content_length", content.length()).toString(),
                    Instant.now().toString(), taskId);
            log.info("mindmap done task={} book={} source={} trace={}", taskId, bookId, source, traceId);
        } catch (BusinessException e) {
            jdbc.update("UPDATE ai_tasks SET status='FAILED', error_message=?, finished_at=? WHERE task_id=?",
                    e.getMessage(), Instant.now().toString(), taskId);
            log.warn("mindmap failed task={} trace={}", taskId, traceId, e);
        } catch (Exception e) {
            jdbc.update("UPDATE ai_tasks SET status='FAILED', error_message=?, finished_at=? WHERE task_id=?",
                    e.toString(), Instant.now().toString(), taskId);
            log.error("mindmap task failed trace={}", traceId, e);
        }
    }

    /** source=summary：取指定版本或最新 SUMMARY 产物 */
    private String loadSummary(String bookId, String sourceVersionId) {
        List<Map<String, Object>> rows;
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            rows = jdbc.queryForList("""
                    SELECT content FROM ai_versions
                    WHERE target_type='book' AND target_id=? AND artifact_type='SUMMARY' AND version_id=?
                    """, bookId, sourceVersionId);
        } else {
            rows = jdbc.queryForList("""
                    SELECT content FROM ai_versions
                    WHERE target_type='book' AND target_id=? AND artifact_type='SUMMARY'
                    ORDER BY version_number DESC LIMIT 1
                    """, bookId);
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND, "指定的摘要版本不存在");
        }
        Object c = rows.get(0).get("content");
        return c == null ? "" : String.valueOf(c);
    }

    /** 章节树节点：路径/标题/层级（对应 chapters 表） */
    private static class ChapterNode {
        final String path;
        final String title;
        final int level;

        ChapterNode(String path, String title, int level) {
            this.path = path;
            this.title = title;
            this.level = level;
        }
    }

    /** source=structure：生成"章节架构=目录"的思维导图。
     *  骨架（# 书名 / ## 章 / ### 节 / - 小节）按书内页序严格拼接，
     *  每个标题下的要点由 LLM 单独提取后由后端插入，LLM 失败时仅输出骨架（结构不丢）。 */
    private String generateStructureMindmap(String bookId, String sourceVersionId) {
        List<ChapterNode> tree = loadChapterTree(bookId);
        Map<String, List<String>> points = new HashMap<>();
        try {
            points = extractChapterPoints(bookId, tree, sourceVersionId);
        } catch (Exception e) {
            log.warn("mindmap structure points failed, fallback to skeleton-only trace={}", TraceContext.current(), e);
        }
        return composeStructureMindmap(bookId, tree, points);
    }

    /** 读 chapters 表章节树，按书内页序（start_page/text_offset）排序，保证与目录一致 */
    private List<ChapterNode> loadChapterTree(String bookId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT chapter_path, title, level, start_page, text_offset FROM chapters
                WHERE book_id=? ORDER BY COALESCE(start_page, 0), COALESCE(text_offset, 0)
                """, bookId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BUILD_FAILED, "该书暂无章节信息，无法按章节生成导图");
        }
        List<ChapterNode> tree = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String path = row.get("chapter_path") == null ? "" : String.valueOf(row.get("chapter_path"));
            String title = row.get("title") == null ? "" : String.valueOf(row.get("title"));
            int level = ((Number) row.get("level")).intValue();
            tree.add(new ChapterNode(path, title, level));
        }
        return tree;
    }

    /** LLM 逐标题提取要点：每行 "路径|要点1~要点2"，解析失败不影响骨架 */
    private Map<String, List<String>> extractChapterPoints(String bookId, List<ChapterNode> tree,
                                                           String sourceVersionId) {
        StringBuilder list = new StringBuilder();
        for (ChapterNode n : tree) {
            list.append(n.path).append('|').append(n.title).append('\n');
        }
        String summary = "";
        try {
            summary = loadSummary(bookId, sourceVersionId);
        } catch (BusinessException ignored) {
            // 无摘要时仅按标题推断要点
        }
        String prompt = "你负责为书籍章节提取要点。下面是章节标题列表（每行：路径|标题）和全书摘要。\n"
                + "请为每个标题生成 1~3 条简明要点（依据摘要与章节标题，宁缺毋滥）。\n"
                + "严格输出格式：每行 = 路径|要点1~要点2（多条要点用 ~ 分隔），路径必须与列表完全一致。\n"
                + "不要输出 Markdown 标记、不要输出解释、不要遗漏标题。\n\n"
                + "【章节列表】\n" + list
                + "\n【全书摘要】\n" + (summary.isBlank() ? "（无）" : summary);
        String raw = doubao.chat(List.of(new DoubaoClient.ChatMessage("user", prompt)), 0.3, 2000);
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String line : raw.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            int idx = l.indexOf('|');
            if (idx <= 0) {
                continue;
            }
            String path = l.substring(0, idx).trim();
            String pts = l.substring(idx + 1).trim();
            if (path.isEmpty() || pts.isEmpty()) {
                continue;
            }
            List<String> ps = new ArrayList<>();
            for (String p : pts.split("~")) {
                String t = p.trim();
                // LLM 可能输出 "路径|标题|要点" 或 "标题|要点"，剥掉残留前缀只留纯要点
                int bar = t.indexOf('|');
                if (bar >= 0) {
                    t = t.substring(bar + 1).trim();
                }
                if (!t.isEmpty()) {
                    ps.add(t);
                }
            }
            if (!ps.isEmpty()) {
                map.put(path, ps);
            }
        }
        return map;
    }

    /** 拼接导图：骨架标题（严格目录序）+ 每个标题下插入要点 */
    private String composeStructureMindmap(String bookId, List<ChapterNode> tree,
                                           Map<String, List<String>> points) {
        String bookTitle = "本书";
        List<Map<String, Object>> books = jdbc.queryForList(
                "SELECT title FROM books WHERE book_id=? AND soft_delete=0", bookId);
        if (!books.isEmpty() && books.get(0).get("title") != null) {
            bookTitle = String.valueOf(books.get(0).get("title"));
        }
        StringBuilder sb = new StringBuilder("# ").append(bookTitle).append("\n");
        for (ChapterNode n : tree) {
            if (n.title.isBlank()) {
                continue;
            }
            // chapters 表的 level 字段可能全为 1（扁平），按标题编号模式推断层级：
            // "2 市场分析"→章(##)，"2.1 市场痛点"→节(###)，"2.1.1 xxx"→小节(-)
            int lvl = n.level <= 0 ? 1 : n.level;
            if (inferChapterLevel(n.title) > lvl) {
                lvl = inferChapterLevel(n.title);
            }
            if (lvl == 1) {
                sb.append("## ").append(n.title).append("\n");
            } else if (lvl == 2) {
                sb.append("### ").append(n.title).append("\n");
            } else {
                sb.append("- ").append(n.title).append("\n");
            }
            List<String> ps = points.get(n.path);
            if (ps != null) {
                for (String p : ps) {
                    sb.append("- ").append(p).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** 按标题编号模式推断层级："2 市场分析"→1；"2.1 市场痛点"→2；"2.1.1"→3；无编号→1 */
    private int inferChapterLevel(String title) {
        String t = title == null ? "" : title.trim();
        if (t.matches("^\\d+\\.\\d+\\.\\d+.*")) {
            return 3;
        }
        if (t.matches("^\\d+\\.\\d+.*")) {
            return 2;
        }
        if (t.matches("^\\d+.*")) {
            return 1;
        }
        return 1;
    }

    /** source=chapter：从 Chroma 读该书切片，按 chapter_path 过滤（精确或前缀匹配，支持多种章节写法） */
    private String loadChapter(String bookId, String chapterPath) {
        long total = chroma.count(COLLECTION_PREFIX + bookId);
        if (total <= 0) {
            throw new BusinessException(ErrorCode.BUILD_FAILED, "该书籍知识库为空，无法生成章节导图");
        }
        List<SearchHit> all = chroma.getAll(COLLECTION_PREFIX + bookId, (int) Math.min(total, 5000));
        String norm = normalizeChapterPath(chapterPath);
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (SearchHit h : all) {
            JsonNode meta = h.getMetadata();
            String cp = meta == null ? "" : meta.path("chapter_path").asText("");
            if (cp.equals(chapterPath) || cp.startsWith(chapterPath)
                    || cp.equals(norm) || cp.startsWith(norm)) {
                sb.append("- ").append(h.getDocument()).append("\n");
                matched++;
            }
        }
        if (matched == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "未找到章节「" + chapterPath + "」的切片，请确认 chapter_path");
        }
        return sb.toString();
    }

    /** 章节写法归一："第一章"/"第1章"/"第一章：xxx" → "1"；纯数字原样返回 */
    private String normalizeChapterPath(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            return s;
        }
        var m = Pattern.compile("第([0-9一二三四五六七八九十百]+)章").matcher(s);
        if (m.find()) {
            return String.valueOf(chineseToNumber(m.group(1)));
        }
        return s;
    }

    /** 中文数字 → 阿拉伯数字（章节号场景足够：支持 一~九十九） */
    private int chineseToNumber(String s) {
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        String[] units = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        int n = 0;
        if (s.contains("百")) {
            String[] p = s.split("百");
            n += chineseToNumber(p[0].isEmpty() ? "一" : p[0]) * 100;
            s = p.length > 1 ? p[1] : "";
            if (s.isEmpty()) {
                return n;
            }
        }
        if (s.contains("十")) {
            String[] p = s.split("十");
            n += (p[0].isEmpty() ? 1 : indexOf(units, p[0])) * 10;
            s = p.length > 1 ? p[1] : "";
            if (s.isEmpty()) {
                return n;
            }
        }
        int idx = indexOf(units, s);
        if (idx >= 0) {
            n += idx;
        }
        return n;
    }

    private int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    private void persistVersion(String taskId, String bookId, String content, String source,
                                String sourceVersionId, String chapterPath) {
        jdbc.update("UPDATE ai_versions SET is_active=0 "
                + "WHERE target_type='book' AND target_id=? AND artifact_type='MINDMAP'", bookId);
        Integer maxVer = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number),0) FROM ai_versions "
                        + "WHERE target_type='book' AND target_id=? AND artifact_type='MINDMAP'",
                Integer.class, bookId);
        int versionNumber = (maxVer == null ? 0 : maxVer) + 1;
        String versionId = "ver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ObjectNode params = mapper.createObjectNode()
                .put("source", source)
                .put("source_version_id", sourceVersionId == null ? "" : sourceVersionId)
                .put("chapter_path", chapterPath == null ? "" : chapterPath);
        // 引用溯源：导图来源（summary 版 / chapter 版 / structure 目录版）+ 范围章节路径
        ObjectNode citations = mapper.createObjectNode()
                .put("source", source == null ? "unknown" : source)
                .put("chapter_path", chapterPath == null ? "" : chapterPath);
        String citationsJson;
        try {
            citationsJson = mapper.writeValueAsString(citations);
        } catch (Exception e) {
            citationsJson = "{}";
        }
        jdbc.update("""
                INSERT INTO ai_versions
                    (version_id, task_id, target_type, target_id, artifact_type, version_number,
                     is_active, content, citations_json, prompt_version_id, params_snapshot_json, created_at)
                VALUES (?, ?, 'book', ?, 'MINDMAP', ?, 1, ?, ?, ?, ?, ?)
                """, versionId, taskId, bookId, versionNumber, content, citationsJson,
                promptVersionRef("mindmap"), params.toString(), Instant.now().toString());
    }

    /** 当前激活的 Prompt 版本引用（深层缺陷⑭）：prompt_versions 以 (name, version) 定位，引用键 name#version */
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

    // ==================== 提交结果（§7.7 Response 202） ====================

    /** 供控制器输出提交结果 */
    public Map<String, Object> taskAccepted(String taskId, String bookId) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("task_id", taskId);
        n.put("book_id", bookId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, trace_id FROM ai_tasks WHERE task_id=?", taskId);
        if (!rows.isEmpty()) {
            n.put("status", rows.get(0).get("status"));
            n.put("trace_id", rows.get(0).get("trace_id"));
        }
        return n;
    }

    // ==================== 查询与编辑 ====================

    /**
     * 获取导图（§7.8）。version="latest"（默认）或具体版本号。
     */
    public Map<String, Object> getMindmap(String bookId, String version) {
        bookId = bookId == null ? "" : bookId.trim();
        if (bookId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "book_id 不能为空");
        }
        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT version_id, version_number, is_active, created_at, content
                FROM ai_versions
                WHERE target_type='book' AND target_id=? AND artifact_type='MINDMAP'
                ORDER BY version_number DESC
                """, bookId);
        if (versions.isEmpty()) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND, "该书暂无思维导图，请先生成");
        }
        return buildVersionPayload(bookId, versions, version);
    }

    /**
     * 编辑导图（§7.9）：直接更新 content，不递增版本号。
     */
    public void updateMindmap(String versionId, String content) {
        versionId = versionId == null ? "" : versionId.trim();
        if (versionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "version_id 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "content 不能为空");
        }
        int n = jdbc.update("""
                UPDATE ai_versions SET content=? WHERE version_id=? AND artifact_type='MINDMAP'
                """, content, versionId);
        if (n == 0) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND, "思维导图版本不存在");
        }
        log.info("mindmap updated version={}", versionId);
    }

    private Map<String, Object> buildVersionPayload(String bookId, List<Map<String, Object>> versions, String version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", bookId);
        List<Object> versionList = new ArrayList<>();
        Map<String, Object> active = null;
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

        Map<String, Object> target;
        if (version == null || version.isBlank() || "latest".equalsIgnoreCase(version)) {
            target = active != null ? active : versions.get(0);
        } else {
            target = null;
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
}
