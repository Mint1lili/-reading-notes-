package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.entity.Book;
import com.readingnotes.service.BookService;
import com.readingnotes.service.BuildService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库构建接口（F2，API 文档 §6）
 * <pre>
 * GET  /books/{bookId}/build/status    构建状态（前端 1~2s 轮询）
 * POST /books/{bookId}/build/retry     重试（断点续传）
 * POST /books/{bookId}/build/cancel    取消
 * GET  /books/{bookId}/chunks          切片预览
 * GET  /books/{bookId}/chapters        章节结构
 * GET  /books/{bookId}/build/preview   构建统计（切片数/字数分布/识别率）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/books/{bookId}")
public class BuildController {

    private final BuildService buildService;
    private final BookService bookService;

    public BuildController(BuildService buildService, BookService bookService) {
        this.buildService = buildService;
        this.bookService = bookService;
    }

    /** 6.1 构建状态 */
    @GetMapping("/build/status")
    public Result<Map<String, Object>> status(@PathVariable String bookId) {
        return Result.ok(buildService.status(bookId));
    }

    /** 6.2 重试构建 */
    @PostMapping("/build/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Map<String, Object>> retry(@PathVariable String bookId,
                                             @RequestBody(required = false) RetryRequest req) {
        Integer fromBatch = req == null ? null : req.getFromBatch();
        return Result.ok(buildService.retry(bookId, fromBatch));
    }

    /** 6.3 取消构建 */
    @PostMapping("/build/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String bookId) {
        return Result.ok(buildService.cancel(bookId));
    }

    /** 6.4 切片预览 */
    @GetMapping("/chunks")
    public Result<Map<String, Object>> chunks(
            @PathVariable String bookId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String chapter) {
        return Result.ok(buildService.chunks(bookId, page, pageSize == null ? 20 : pageSize, chapter));
    }

    /** 6.5 章节结构 */
    @GetMapping("/chapters")
    public Result<Map<String, Object>> chapters(@PathVariable String bookId) {
        return Result.ok(buildService.chapters(bookId));
    }

    /** 构建统计（F2.3 预览校验：切片数 / 字数分布 / 章节识别率） */
    @GetMapping("/build/preview")
    public Result<Map<String, Object>> preview(@PathVariable String bookId) {
        Book book = bookService.detail(bookId);
        return Result.ok(buildService.previewStats(book));
    }

    @Data
    public static class RetryRequest {
        private Integer fromBatch;
    }
}
