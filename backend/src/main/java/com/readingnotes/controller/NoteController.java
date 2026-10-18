package com.readingnotes.controller;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import com.readingnotes.entity.Note;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.TagRepository;
import com.readingnotes.service.NoteExportService;
import com.readingnotes.service.NoteFromAiService;
import com.readingnotes.service.NoteLinkService;
import com.readingnotes.service.NoteService;
import com.readingnotes.service.TagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记管理接口（API 文档 §9）
 *
 * <p>第一阶段已实现（对应文档 9.1~9.7）：
 * <ul>
 *   <li>POST /notes                新建笔记（9.1）</li>
 *   <li>GET /notes                 笔记列表（9.2）</li>
 *   <li>GET /notes/{note_id}       笔记详情（9.3）</li>
 *   <li>PATCH /notes/{note_id}     编辑笔记（9.4，editor_version 乐观锁）</li>
 *   <li>DELETE /notes/{note_id}    软删除（9.5）</li>
 *   <li>POST /notes/{note_id}/restore  恢复（9.6）</li>
 *   <li>DELETE /notes/{note_id}/purge  永久删除（9.7）</li>
 * </ul>
 * 待后续阶段：9.8 回流开关、9.9 AI 产物存为笔记、9.11 导出。
 * 第二阶段已实现：9.12 标签（创建笔记 tag_names 自动打标 + 列表 tag 过滤 + tags 字段填充）。
 * 第三阶段已实现：9.10 双向链接（查询/创建 + 详情 links + 列表 link_count）。
 */
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;
    private final TagService tagService;
    private final TagRepository tagRepository;
    private final NoteLinkService noteLinkService;
    private final NoteLinkRepository noteLinkRepository;
    private final NoteExportService noteExportService;
    private final NoteFromAiService noteFromAiService;

    public NoteController(NoteService noteService, NoteRepository noteRepository,
                          TagService tagService, TagRepository tagRepository,
                          NoteLinkService noteLinkService, NoteLinkRepository noteLinkRepository,
                          NoteExportService noteExportService,
                          NoteFromAiService noteFromAiService) {
        this.noteService = noteService;
        this.noteRepository = noteRepository;
        this.tagService = tagService;
        this.tagRepository = tagRepository;
        this.noteLinkService = noteLinkService;
        this.noteLinkRepository = noteLinkRepository;
        this.noteExportService = noteExportService;
        this.noteFromAiService = noteFromAiService;
    }

    // ==================== 9.1 新建笔记 ====================

    /** 请求体：{ title?, content(必填), source_type?, source_book_id?, source_page?, source_chunk_id?, tag_names? } */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Object>> create(@RequestBody Note note) {
        Note created = noteService.create(note);
        // 阶段 2：tag_names 自动打标（不存在则自动创建，幂等）
        if (note.getTagNames() != null && !note.getTagNames().isEmpty()) {
            tagService.bindTags(created.getNoteId(), note.getTagNames());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("note_id", created.getNoteId());
        data.put("title", created.getTitle());
        data.put("is_indexed", created.getIsIndexed());
        data.put("index_status", created.getIndexStatus());
        data.put("editor_version", created.getEditorVersion());
        data.put("created_at", created.getCreatedAt());
        return Result.ok(data);
    }

    // ==================== 9.2 笔记列表 ====================

    /**
     * 请求示例：GET /notes?book_id=&source_type=&keyword=&tag=&indexed=true&page=1&page_size=20
     *
     * <p>indexed 用 String 接收后手工解析：非法值（如 indexed=abc）统一返回 400/10001，
     * 避免落到兜底 500。
     */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String book_id,
            @RequestParam(required = false) String source_type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String indexed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Boolean indexedFlag = parseIndexed(indexed);
        List<Note> notes = noteService.list(book_id, source_type, keyword, tag,
                indexedFlag, page, pageSize);
        // 批量查标签与链接数，避免列表逐行 N+1 查询
        Map<String, List<Tag>> tagsMap = tagRepository.findByNoteIds(
                notes.stream().map(Note::getNoteId).toList());
        Map<String, Integer> linkCountMap = noteLinkRepository.countLinksByNoteIds(
                notes.stream().map(Note::getNoteId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Note n : notes) {
            items.add(toListItem(n, tagsMap.getOrDefault(n.getNoteId(), List.of()),
                    linkCountMap.getOrDefault(n.getNoteId(), 0)));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", noteService.count(book_id, source_type, keyword, tag, indexedFlag));
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        return Result.ok(data);
    }

    /** 列表项：正文只给 200 字预览；tags 为真实标签；link_count 为出链+入链总数 */
    private Map<String, Object> toListItem(Note n, List<Tag> tags, int linkCount) {
        Map<String, Object> item = new HashMap<>();
        item.put("note_id", n.getNoteId());
        item.put("title", n.getTitle());
        item.put("content_preview", preview(n.getContent(), 200));
        item.put("source_type", n.getSourceType());
        item.put("source_book_title", noteRepository.sourceBookTitle(n.getSourceBookId()));
        item.put("source_page", n.getSourcePage());
        item.put("tags", tags);
        item.put("is_indexed", n.getIsIndexed() != null && n.getIsIndexed() == 1);
        item.put("index_status", n.getIndexStatus());
        item.put("word_count", n.getWordCount());
        item.put("link_count", linkCount);              // 阶段 3：出链+入链总数
        item.put("created_at", n.getCreatedAt());
        item.put("updated_at", n.getUpdatedAt());
        return item;
    }

    // ==================== 9.3 笔记详情 ====================

    @GetMapping("/{noteId}")
    public Result<Map<String, Object>> detail(@PathVariable String noteId) {
        Note n = noteService.detail(noteId);
        Map<String, Object> data = new HashMap<>();
        data.put("note_id", n.getNoteId());
        data.put("title", n.getTitle());
        data.put("content", n.getContent());
        data.put("source_type", n.getSourceType());
        data.put("source_book_id", n.getSourceBookId());
        data.put("source_book_title", noteRepository.sourceBookTitle(n.getSourceBookId()));
        data.put("source_page", n.getSourcePage());
        data.put("source_chunk_id", n.getSourceChunkId());
        // 来源书被删（或来源书不存在）时置 false，前端跳转按钮置灰（F1.2 第 3 步）
        data.put("can_jump_to_source", noteRepository.sourceBookExists(n.getSourceBookId()));
        data.put("tags", tagRepository.findByNoteId(noteId));
        data.put("is_indexed", n.getIsIndexed() != null && n.getIsIndexed() == 1);
        data.put("index_status", n.getIndexStatus());
        data.put("word_count", n.getWordCount());
        data.put("editor_version", n.getEditorVersion());
        // 阶段 3：详情 links 展示出链（目标被永久删除时标题兜底、jumpable=false）
        data.put("links", noteLinkService.list(noteId).get("outgoing"));
        data.put("created_at", n.getCreatedAt());
        data.put("updated_at", n.getUpdatedAt());
        return Result.ok(data);
    }

    // ==================== 9.4 编辑笔记 ====================

    /**
     * 请求体：{ title?, content?, editor_version(必填), tag_names? }；版本不匹配返回 50004
     *
     * <p>tag_names 语义为「全量覆盖」：传了就以此为准（空数组=清空标签），不传则不动原有标签。
     */
    @PatchMapping("/{noteId}")
    public Result<Map<String, Object>> update(@PathVariable String noteId,
                                              @RequestBody Map<String, Object> body) {
        Integer expectedVersion = (body.get("editor_version") == null) ? null
                : ((Number) body.get("editor_version")).intValue();
        if (expectedVersion == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "editor_version 必填");
        }
        if (body.get("editor_version") != null && !(body.get("editor_version") instanceof Number)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "editor_version 必须为数字");
        }
        String title = body.get("title") == null ? null : body.get("title").toString();
        String content = body.get("content") == null ? null : body.get("content").toString();
        Note updated = noteService.update(noteId, title, content, expectedVersion);
        // 标签覆盖：仅当请求显式携带 tag_names 时才变更（缺失则保持原标签不变）
        if (body.containsKey("tag_names")) {
            tagService.replaceTags(noteId, parseTagNames(body.get("tag_names")));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("note_id", updated.getNoteId());
        data.put("title", updated.getTitle());
        data.put("editor_version", updated.getEditorVersion());
        data.put("word_count", updated.getWordCount());
        data.put("tags", tagRepository.findByNoteId(noteId));   // 回显当前标签，便于前端刷新
        data.put("updated_at", updated.getUpdatedAt());
        return Result.ok(data);
    }

    // ==================== 9.5 软删除 / 9.6 恢复 / 9.7 永久删除 ====================

    /** 软删除（回收站）：文档约定 204 无内容 */
    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Result<Void> delete(@PathVariable String noteId) {
        noteService.delete(noteId);
        return Result.ok();
    }

    @PostMapping("/{noteId}/restore")
    public Result<Void> restore(@PathVariable String noteId) {
        noteService.restore(noteId);
        return Result.ok();
    }

    /** 永久删除：文档约定 204 无内容 */
    @DeleteMapping("/{noteId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Result<Void> purge(@PathVariable String noteId) {
        noteService.purge(noteId);
        return Result.ok();
    }

    // ==================== 9.9 AI 产物一键存为笔记 ====================

    /**
     * 请求体：{ source_type(AI_SUMMARY|AI_QA|AI_MINDMAP), source_ai_version_id?,
     *          session_id?, title?, auto_tag? }
     * source_ai_version_id 与 session_id 至少填一个：前者取 ai_versions 正式产物，
     * 后者取 chat_messages 最后一轮问答对。
     */
    @PostMapping("/from-ai")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Object>> createFromAi(@RequestBody Map<String, Object> body) {
        String sourceType = str(body.get("source_type"));
        String versionId = str(body.get("source_ai_version_id"));
        String sessionId = str(body.get("session_id"));
        String title = str(body.get("title"));
        boolean autoTag = Boolean.parseBoolean(String.valueOf(body.getOrDefault("auto_tag", false)));

        Note n = noteFromAiService.createFromAi(sourceType, versionId, sessionId, title, autoTag);

        Map<String, Object> data = new HashMap<>();
        data.put("note_id", n.getNoteId());
        data.put("title", n.getTitle());
        data.put("source_type", n.getSourceType());
        data.put("source_ai_version_id", n.getSourceAiVersionId());
        data.put("source_book_id", n.getSourceBookId());
        data.put("word_count", n.getWordCount());
        data.put("is_indexed", n.getIsIndexed() != null && n.getIsIndexed() == 1);
        data.put("index_status", n.getIndexStatus());
        data.put("editor_version", n.getEditorVersion());
        data.put("tags", tagRepository.findByNoteId(n.getNoteId()));
        data.put("created_at", n.getCreatedAt());
        return Result.ok(data);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    // ==================== 9.11 导出 ====================

    /**
     * 请求体：{ note_ids?, book_id?, scope(selected|book), format(markdown|pdf),
     *          include_front_matter? }
     * 返回 download_url / file_name / file_size / expires_at / note_count。
     */
    @PostMapping("/export")
    public Result<Map<String, Object>> exportNotes(@RequestBody Map<String, Object> body) {
        List<String> noteIds = toStringList(body.get("note_ids"));
        String bookId = body.get("book_id") == null ? null : body.get("book_id").toString();
        String scope = body.get("scope") == null ? null : body.get("scope").toString();
        String format = body.get("format") == null ? null : body.get("format").toString();
        boolean frontMatter = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("include_front_matter", false)));
        return Result.ok(noteExportService.export(noteIds, bookId, scope, format, frontMatter));
    }

    /** 把请求里的字符串数组统一转成 List&lt;String&gt;（非数组抛 10001） */
    private static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof java.util.Collection<?> col)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "note_ids 必须为字符串数组");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : col) {
            if (item != null && !item.toString().isBlank()) {
                ids.add(item.toString().trim());
            }
        }
        return ids;
    }

    // ==================== 9.10 双向链接 ====================

    /** 查询：{ outgoing: [...], incoming: [...] } */
    @GetMapping("/{noteId}/links")
    public Result<Map<String, Object>> links(@PathVariable String noteId) {
        return Result.ok(noteLinkService.list(noteId));
    }

    /** 创建：{ target_note_id(必填), context? }；两端笔记不存在返回 50001；同对幂等 */
    @PostMapping("/{noteId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Void> createLink(@PathVariable String noteId,
                                   @RequestBody Map<String, Object> body) {
        String targetNoteId = body.get("target_note_id") == null ? null
                : body.get("target_note_id").toString();
        String context = body.get("context") == null ? null : body.get("context").toString();
        noteLinkService.create(noteId, targetNoteId, context);
        return Result.ok();
    }

    // ==================== 工具 ====================

    /**
     * 解析 tag_names：必须是字符串数组；非数组抛 10001。
     * 空数组返回空列表（表示清空标签），元素中的空白项忽略。
     */
    private static List<String> parseTagNames(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof java.util.Collection<?> col)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "tag_names 必须为字符串数组");
        }
        List<String> names = new ArrayList<>();
        for (Object item : col) {
            if (item == null || item.toString().isBlank()) {
                continue;
            }
            names.add(item.toString().trim());
        }
        return names;
    }

    /**
     * 解析 indexed 查询参数：null/空 → 不过滤；"true"/"false"（忽略大小写）→ 对应布尔值；
     * 其他值 → 抛 10001 参数错误。
     */
    private static Boolean parseIndexed(String indexed) {
        if (indexed == null || indexed.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(indexed) || "false".equalsIgnoreCase(indexed)) {
            return Boolean.parseBoolean(indexed);
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "indexed 只能为 true 或 false");
    }

    /** 正文预览：截取前 maxLen 字符，超出补省略号（不破坏 Markdown 结构） */
    private static String preview(String content, int maxLen) {
        if (content == null) {
            return null;
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
    }
}
