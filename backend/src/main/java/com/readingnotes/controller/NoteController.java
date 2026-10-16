package com.readingnotes.controller;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import com.readingnotes.entity.Note;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.TagRepository;
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
 * 待后续阶段：9.8 回流开关、9.9 AI 产物存为笔记、9.10 双向链接、9.11 导出。
 * 第二阶段已实现：9.12 标签（创建笔记 tag_names 自动打标 + 列表 tag 过滤 + tags 字段填充）。
 */
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;
    private final TagService tagService;
    private final TagRepository tagRepository;

    public NoteController(NoteService noteService, NoteRepository noteRepository,
                          TagService tagService, TagRepository tagRepository) {
        this.noteService = noteService;
        this.noteRepository = noteRepository;
        this.tagService = tagService;
        this.tagRepository = tagRepository;
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

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String book_id,
            @RequestParam(required = false) String source_type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<Note> notes = noteService.list(book_id, source_type, keyword, tag, page, pageSize);
        // 批量查标签，避免列表逐行 N+1 查询
        Map<String, List<Tag>> tagsMap = tagRepository.findByNoteIds(
                notes.stream().map(Note::getNoteId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Note n : notes) {
            items.add(toListItem(n, tagsMap.getOrDefault(n.getNoteId(), List.of())));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", noteService.count(book_id, source_type, keyword, tag));
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        return Result.ok(data);
    }

    /** 列表项：正文只给 200 字预览；tags 为真实标签；link_count 由阶段 3 填充 */
    private Map<String, Object> toListItem(Note n, List<Tag> tags) {
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
        item.put("link_count", 0);                      // 阶段 3 双向链接
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
        data.put("links", new ArrayList<>());           // 阶段 3 双向链接
        data.put("created_at", n.getCreatedAt());
        data.put("updated_at", n.getUpdatedAt());
        return Result.ok(data);
    }

    // ==================== 9.4 编辑笔记 ====================

    /** 请求体：{ title?, content?, editor_version(必填) }；版本不匹配返回 50004 */
    @PatchMapping("/{noteId}")
    public Result<Map<String, Object>> update(@PathVariable String noteId,
                                              @RequestBody Map<String, Object> body) {
        Integer expectedVersion = (body.get("editor_version") == null) ? null
                : ((Number) body.get("editor_version")).intValue();
        if (expectedVersion == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "editor_version 必填");
        }
        String title = body.get("title") == null ? null : body.get("title").toString();
        String content = body.get("content") == null ? null : body.get("content").toString();
        Note updated = noteService.update(noteId, title, content, expectedVersion);
        Map<String, Object> data = new HashMap<>();
        data.put("note_id", updated.getNoteId());
        data.put("title", updated.getTitle());
        data.put("editor_version", updated.getEditorVersion());
        data.put("word_count", updated.getWordCount());
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

    // ==================== 工具 ====================

    /** 正文预览：截取前 maxLen 字符，超出补省略号（不破坏 Markdown 结构） */
    private static String preview(String content, int maxLen) {
        if (content == null) {
            return null;
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
    }
}
