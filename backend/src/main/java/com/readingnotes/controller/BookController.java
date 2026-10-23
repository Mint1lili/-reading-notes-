package com.readingnotes.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.readingnotes.common.Result;
import com.readingnotes.common.TraceContext;
import com.readingnotes.entity.Book;
import com.readingnotes.service.BookService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍管理接口（F1，API 文档 §5）。
 * <p>
 * 只做参数接收与视图组装，业务规则全部在 {@link BookService}；因此本层不抛业务异常，
 * 统一由 GlobalExceptionHandler 转换。路由顺序有硬约束：{@code /trash} 必须声明在
 * {@code /{bookId}} 之前，否则会被当成 bookId 匹配。
 * <pre>
 * POST   /books/upload                 上传 PDF
 * GET    /books                        列表
 * GET    /books/trash                  回收站（必须声明在 /{bookId} 之前）
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

    /** 回收站保留天数，与 build.trash-retain-days 保持一致（展示用） */
    private static final int TRASH_RETAIN_DAYS = 7;

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    // =========================================================================
    // 5.1 上传
    // =========================================================================

    /**
     * 上传 PDF 并触发构建。
     * <p>
     * 同名书籍默认返回 409（20003），前端弹确认后带 {@code confirm_duplicate=true} 重试。
     *
     * @param file              PDF 文件，≤50MB
     * @param title             书名，为空时取 PDF 元数据或文件名
     * @param author            作者，可空
     * @param confirmDuplicate  是否已确认"同名仍要上传"
     * @return 201 + book_id / status / build_progress / total_pages
     */
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
        data.put("trace_id", TraceContext.current());
        return Result.ok(data);
    }

    // =========================================================================
    // 5.2 列表
    // =========================================================================

    /**
     * 书籍列表（分页，默认按创建时间倒序）。
     *
     * @param status   状态过滤，空则全部（不含回收站）
     * @param keyword  书名/作者模糊匹配
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数，服务端夹紧到 1~100
     * @return total / page / page_size / items
     */
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
        // 与查询时实际生效的 page_size 保持一致，避免回显值与真实分页不符
        data.put("page_size", Math.min(Math.max(1, pageSize), 100));
        data.put("items", items);
        return Result.ok(data);
    }

    // =========================================================================
    // 5.8 回收站（必须放在 /{bookId} 之前，否则 trash 会被当成 bookId）
    // =========================================================================

    /**
     * 回收站列表：列出软删未过期的书，供恢复或立即清除。
     *
     * @return total / items / retain_days
     */
    @GetMapping("/trash")
    public Result<Map<String, Object>> trash() {
        List<Map<String, Object>> items = bookService.trash();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        data.put("retain_days", TRASH_RETAIN_DAYS);
        return Result.ok(data);
    }

    // =========================================================================
    // 5.10 删除预检 / 5.11 原文件
    // =========================================================================

    /**
     * 删除预检：二次确认弹窗要列清影响面（笔记数、切片数、文件大小、是否构建中）。
     *
     * @param bookId 书籍 id
     * @return 影响面统计；书籍不存在时抛 20005
     */
    @GetMapping("/{bookId}/delete-preview")
    public Result<Map<String, Object>> deletePreview(@PathVariable String bookId) {
        return Result.ok(bookService.deletePreview(bookId));
    }

    /**
     * 原书文件流（F3 引用溯源「点击页码看原文」用）。
     * <p>
     * 用 {@code inline} + RFC 5987 文件名，浏览器内直接预览 PDF 而不是下载；
     * 软删除的书在这里直接 404——与笔记侧「查看原文」置灰的判定一致。
     *
     * @param bookId 书籍 id
     * @return PDF 二进制流
     */
    @GetMapping("/{bookId}/file")
    public ResponseEntity<Resource> file(@PathVariable String bookId) {
        Book book = bookService.detail(bookId);
        Path pdf = bookService.resolvePdf(bookId);
        // 中文书名必须 URL 编码，否则 header 非法；'+' 需还原为 %20 以符合 header 语义
        String encodedName = URLEncoder.encode(
                book.getTitle() == null ? bookId : book.getTitle(),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName + ".pdf")
                .body(new FileSystemResource(pdf));
    }

    // =========================================================================
    // 5.3 详情 / 5.4 编辑
    // =========================================================================

    /** 书籍详情（含构建状态、切片数、章节识别率） */
    @GetMapping("/{bookId}")
    public Result<Book> detail(@PathVariable String bookId) {
        return Result.ok(bookService.detail(bookId));
    }

    /**
     * 编辑书名/作者。
     * <p>
     * 只允许改元数据；改书名不会影响已建好的向量库，也不会触发重建。
     */
    @PatchMapping("/{bookId}")
    public Result<Book> update(@PathVariable String bookId, @RequestBody UpdateBookRequest req) {
        return Result.ok(bookService.updateMeta(bookId, req.getTitle(), req.getAuthor()));
    }

    // =========================================================================
    // 5.5 软删除 / 5.6 恢复 / 5.7 永久删除
    // =========================================================================

    /**
     * 软删除：进回收站，保留 7 天。
     *
     * @param bookId 书籍 id
     * @param req    可选；{@code keep_notes=true}（默认）保留关联笔记，只解除书籍绑定
     */
    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String bookId,
                       @RequestBody(required = false) DeleteBookRequest req) {
        boolean keepNotes = req == null || req.getKeepNotes() == null || req.getKeepNotes();
        bookService.softDelete(bookId, keepNotes);
    }

    /**
     * 从回收站恢复。
     * <p>
     * 原文件仍在且切片完整时直接回到 READY；否则自动重新触发构建。
     */
    @PostMapping("/{bookId}/restore")
    public Result<Book> restore(@PathVariable String bookId) {
        return Result.ok(bookService.restore(bookId));
    }

    /** 永久删除：清理原文件、章节、切片、向量集合与笔记绑定，不可恢复 */
    @DeleteMapping("/{bookId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable String bookId) {
        bookService.purge(bookId);
    }

    // =========================================================================
    // 5.9 重建
    // =========================================================================

    /**
     * 重建知识库（异步，202 立即返回）。
     *
     * @param bookId 书籍 id
     * @param req    可选切片参数；不传则沿用上次或默认粒度
     * @return book_id / status / build_progress
     */
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
    // 视图组装与请求体
    // =========================================================================

    /** 列表项视图：字段名与 API 文档 §5.2 对齐（下划线风格），不直接返回实体 */
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

    /** 编辑请求体；字段为 null 表示不修改 */
    @Data
    public static class UpdateBookRequest {
        private String title;
        private String author;
    }

    /** 删除请求体 */
    @Data
    public static class DeleteBookRequest {
        /** 前端按 API 文档传 keep_notes，这里沿用别名接收 */
        @JsonAlias("keep_notes")
        private Boolean keepNotes;
    }

    /** 重建请求体 */
    @Data
    public static class RebuildRequest {
        /**
         * 切片粒度。前端/文档用 chunk_size，若不接别名会被 Jackson 静默丢弃，
         * 导致"选细粒度重建"实际仍按默认 600 字切片——必须保留别名。
         */
        @JsonAlias("chunk_size")
        private Integer chunkSize;
        private Integer overlap;
    }
}
