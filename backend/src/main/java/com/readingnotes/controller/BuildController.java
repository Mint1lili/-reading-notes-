package com.readingnotes.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.readingnotes.build.BuildPreviewService;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 知识库构建接口（F2，API 文档 §6）。
 * <p>
 * 构建是异步的：上传/重建/重试接口都立即返回 202，前端靠 {@code /build/status} 轮询推进度。
 * 本层不含构建逻辑，只做参数解析与"内存试算 / 落库统计"两条路径的分发。
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
    private final BuildPreviewService previewService;

    public BuildController(BuildService buildService, BookService bookService,
                           BuildPreviewService previewService) {
        this.buildService = buildService;
        this.bookService = bookService;
        this.previewService = previewService;
    }

    /** 6.1 构建状态：前端每 1~2s 轮询，返回体直接作为进度条数据源 */
    @GetMapping("/build/status")
    public Result<Map<String, Object>> status(@PathVariable String bookId) {
        return Result.ok(buildService.status(bookId));
    }

    /**
     * 6.2 重试构建（断点续传）。
     *
     * @param bookId 书籍 id
     * @param req    可选；不传 from_batch 时从 last_completed_batch 续跑
     */
    @PostMapping("/build/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Map<String, Object>> retry(@PathVariable String bookId,
                                             @RequestBody(required = false) RetryRequest req) {
        Integer fromBatch = req == null ? null : req.getFromBatch();
        return Result.ok(buildService.retry(bookId, fromBatch));
    }

    /**
     * 6.3 取消构建。
     * <p>
     * 只置取消标记，worker 在批次边界生效；已完成的批次不回滚，便于再次续跑。
     */
    @PostMapping("/build/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String bookId) {
        return Result.ok(buildService.cancel(bookId));
    }

    /**
     * 6.4 切片预览（分页）。
     *
     * @param chapter 章节路径前缀，用于只看某一章；空则全书
     */
    @GetMapping("/chunks")
    public Result<Map<String, Object>> chunks(
            @PathVariable String bookId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String chapter) {
        return Result.ok(buildService.chunks(bookId, page, pageSize == null ? 20 : pageSize, chapter));
    }

    /** 6.5 章节结构：扁平列表 + 按 path 还原的树，供目录侧栏渲染 */
    @GetMapping("/chapters")
    public Result<Map<String, Object>> chapters(@PathVariable String bookId) {
        return Result.ok(buildService.chapters(bookId));
    }

    /**
     * 构建统计 / 构建前试算（F2.3）。
     * <ul>
     *   <li>不带参数 → 已落库切片的实测统计（构建后回看）</li>
     *   <li>带 {@code chunk_size} 或 {@code granularity} → 内存试算，不落库、不调 Embedding，
     *       用于"先看切片再决定粒度"</li>
     * </ul>
     *
     * @param chunkSize   直接指定字数（优先于 granularity）
     * @param overlap     重叠字数，空则用默认值
     * @param granularity 粗/中/细三档，映射到 1000/600/300
     */
    @GetMapping("/build/preview")
    public Result<Map<String, Object>> preview(
            @PathVariable String bookId,
            @RequestParam(name = "chunk_size", required = false) Integer chunkSize,
            @RequestParam(name = "overlap", required = false) Integer overlap,
            @RequestParam(required = false) String granularity) {
        Integer size = chunkSize != null ? chunkSize
                : BuildPreviewService.sizeOfGranularity(granularity);
        // 三个参数都没给：说明用户在看已构建结果，而不是试算新粒度
        if (size == null && granularity == null && overlap == null) {
            Book book = bookService.detail(bookId);
            return Result.ok(buildService.previewStats(book));
        }
        try {
            Path pdf = bookService.resolvePdf(bookId);
            return Result.ok(previewService.preview(bookId, pdf.toFile(), size, overlap));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PDF_DAMAGED, "读取原书文件失败，请重试");
        }
    }

    /** 重试请求体 */
    @Data
    public static class RetryRequest {
        /** 前端按 API 文档传 from_batch；不接别名会被静默丢弃，变成"从断点续跑"而非指定批次 */
        @JsonAlias("from_batch")
        private Integer fromBatch;
    }
}
