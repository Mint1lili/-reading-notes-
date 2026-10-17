package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.entity.Book;
import com.readingnotes.service.BookService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍管理接口（F1，API 文档 §5）
 * <pre>
 * POST   /books/upload                 上传 PDF
 * GET    /books                        列表
 * GET    /books/trash                  回收站（注意：必须声明在 /{bookId} 之前）
 * GET    /books/{bookId}               详情
 * PATCH  /books/{bookId}               编辑书名/作者
 * DELETE /books/{bookId}               软删除（级联事务）
 * POST   /books/{bookId}/restore       恢复
 * DELETE /books/{bookId}/purge         永久删除
 * POST   /books/{bookId}/rebuild       重建知识库
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    // =========================================================================
    // 5.1 上传
    // =========================================================================

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "confirm_duplicate", defaultValue = "false") boolean confirmDuplicate) {

        Book book = bookService.upload(file, title, author, confirmDuplicate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", book.getBookId());
        data.put("title", book.getTitle());
        data.put("status", book.getStatus());
        data.put("build_progress", book.getBuildProgress());
        data.put("total_pages", book.getTotalPages());
        data.put("trace_id", com.readingnotes.common.TraceContext.current());
        return Result.ok(data);
    }

    // =========================================================================
    // 5.2 列表
    // =========================================================================

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") Integer page_size) {
        int pageSize = page_size == null ? 20 : page_size;
        List<Book> books = bookService.list(status, keyword, page, pageSize);
        // 一次 SQL 聚合全部 note_count，避免逐行查询（N+1）
        Map<String, Integer> noteCounts = bookService.noteCounts(
                books.stream().map(Book::getBookId).toList());

        List<Map<String, Object>> items = new ArrayList<>(books.size());
        for (Book b : books) {
            Map<String, Object> m = toListItem(b, noteCounts.getOrDefault(b.getBookId(), 0));
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", bookService.count(status, keyword));
        data.put("page", Math.max(1, page));
        data.put("page_size", Math.min(Math.max(1, pageSize), 100));
        data.put("items", items);
        return Result.ok(data);
    }

    // =========================================================================
    // 5.8 回收站（必须放在 /{bookId} 之前，否则 trash 会被当成 bookId）
    // =========================================================================

    @GetMapping("/trash")
    public Result<Map<String, Object>> trash() {
        List<Map<String, Object>> items = bookService.trash();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        data.put("retain_days", 7);
        return Result.ok(data);
    }

    // =========================================================================
    // 5.3 详情 / 5.4 编辑
    // =========================================================================

    @GetMapping("/{bookId}")
    public Result<Book> detail(@PathVariable String bookId) {
        return Result.ok(bookService.detail(bookId));
    }

    @PatchMapping("/{bookId}")
    public Result<Book> update(@PathVariable String bookId, @RequestBody UpdateBookRequest req) {
        return Result.ok(bookService.updateMeta(bookId, req.getTitle(), req.getAuthor()));
    }

    // =========================================================================
    // 5.5 软删除 / 5.6 恢复 / 5.7 永久删除
    // =========================================================================

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String bookId,
                       @RequestBody(required = false) DeleteBookRequest req) {
        boolean keepNotes = req == null || req.getKeepNotes() == null || req.getKeepNotes();
        bookService.softDelete(bookId, keepNotes);
    }

    @PostMapping("/{bookId}/restore")
    public Result<Book> restore(@PathVariable String bookId) {
        return Result.ok(bookService.restore(bookId));
    }

    @DeleteMapping("/{bookId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable String bookId) {
        bookService.purge(bookId);
    }

    // =========================================================================
    // 5.9 重建
    // =========================================================================

    @PostMapping("/{bookId}/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Map<String, Object>> rebuild(@PathVariable String bookId,
                                               @RequestBody(required = false) RebuildRequest req) {
        Integer chunkSize = req == null ? null : req.getChunkSize();
        Integer overlap = req == null ? null : req.getOverlap();
        Book book = bookService.rebuild(bookId, chunkSize, overlap);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", book.getBookId());
        data.put("status", book.getStatus());
        data.put("build_progress", book.getBuildProgress());
        return Result.ok(data);
    }

    // =========================================================================
    // 请求体
    // =========================================================================

    private Map<String, Object> toListItem(Book b, int noteCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("book_id", b.getBookId());
        m.put("title", b.getTitle());
        m.put("author", b.getAuthor());
        m.put("status", b.getStatus());
        m.put("build_sub_stage", b.getBuildSubStage());
        m.put("build_progress", b.getBuildProgress());
        m.put("error_code", b.getErrorCode());
        m.put("error_message", b.getErrorMessage());
        m.put("total_pages", b.getTotalPages());
        m.put("chunk_count", b.getChunkCount());
        m.put("chapter_recognized_rate", b.getChapterRecognizedRate());
        m.put("file_size", b.getFileSize());
        m.put("note_count", noteCount);
        m.put("created_at", b.getCreatedAt());
        m.put("updated_at", b.getUpdatedAt());
        m.put("last_ready_at", b.getLastReadyAt());
        return m;
    }

    @Data
    public static class UpdateBookRequest {
        private String title;
        private String author;
    }

    @Data
    public static class DeleteBookRequest {
        private Boolean keepNotes;
    }

    @Data
    public static class RebuildRequest {
        private Integer chunkSize;
        private Integer overlap;
    }
}
