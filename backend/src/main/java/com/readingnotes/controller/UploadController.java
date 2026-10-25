package com.readingnotes.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import com.readingnotes.common.TraceContext;
import com.readingnotes.entity.Book;
import com.readingnotes.service.BookService;
import com.readingnotes.service.UploadSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片上传接口（F1.1 边界：上传断网可断点续传）。
 *
 * <pre>
 * POST   /api/v1/books/upload/init                  初始化会话
 * PUT    /api/v1/books/upload/{uploadId}/chunk      上传单片（index 从 0 开始）
 * GET    /api/v1/books/upload/{uploadId}            查询进度/缺失片（断线重连用）
 * POST   /api/v1/books/upload/{uploadId}/complete   合并并把书入库
 * DELETE /api/v1/books/upload/{uploadId}            放弃上传
 * </pre>
 *
 * <p>与 {@code POST /books/upload} 的关系：小文件走一次性上传，大文件走这里；
 * 两条路径最终都落到 {@link BookService#registerBook} 同一条入库链路，校验与副作用完全一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/books/upload")
public class UploadController {

    private final UploadSessionService uploadService;
    private final BookService bookService;

    public UploadController(UploadSessionService uploadService, BookService bookService) {
        this.uploadService = uploadService;
        this.bookService = bookService;
    }

    /** 初始化：返回 uploadId 与已收片号（同文件重复 init 会复用未过期会话） */
    @PostMapping("/init")
    public Result<Map<String, Object>> init(@RequestBody InitRequest req) {
        return Result.ok(uploadService.init(req.getFileName(), req.getFileSize()));
    }

    /** 上传单片：幂等，重复上传同一下标覆盖旧片 */
    @PutMapping("/{uploadId}/chunk")
    public Result<Map<String, Object>> chunk(@PathVariable String uploadId,
                                             @RequestParam("index") int index,
                                             @RequestParam("file") MultipartFile file) {
        return Result.ok(uploadService.saveChunk(uploadId, index, file));
    }

    /** 查询进度：前端刷新/断网重连后据此只补 missing 里的片 */
    @GetMapping("/{uploadId}")
    public Result<Map<String, Object>> status(@PathVariable String uploadId) {
        return Result.ok(uploadService.status(uploadId));
    }

    /**
     * 合并分片并入库。
     * 合并成功即删除全部临时分片；入库失败（重名未确认/扫描件等）也清理会话，
     * 避免用户确认重名后还要重新传一遍。
     */
    @PostMapping("/{uploadId}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Object>> complete(@PathVariable String uploadId,
                                                @RequestBody(required = false) CompleteRequest req) {
        UploadSessionService.Session session = uploadService.require(uploadId);
        Path merged = uploadService.assemble(uploadId);
        boolean keepSession = false;
        try {
            // 文件名与声明大小一律取自服务端会话，不信任前端重复提交的参数
            Book book = bookService.registerBook(merged, session.getFileName(), session.getFileSize(),
                    req == null ? null : req.getTitle(),
                    req == null ? null : req.getAuthor(),
                    req != null && Boolean.TRUE.equals(req.getConfirmDuplicate()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("book_id", book.getBookId());
            data.put("title", book.getTitle());
            data.put("status", book.getStatus());
            data.put("build_progress", book.getBuildProgress());
            data.put("total_pages", book.getTotalPages());
            data.put("trace_id", TraceContext.current());
            return Result.ok(data);
        } catch (BusinessException e) {
            // 重名（20003）：保留已上传分片，前端确认后直接再次 complete，不必把 50MB 重传一遍
            keepSession = e.getCode() == ErrorCode.DUPLICATE_BOOK.getCode();
            throw e;
        } finally {
            try {
                Files.deleteIfExists(merged);
            } catch (IOException ignored) {
                log.warn("cleanup merged temp failed: {}", merged);
            }
            if (!keepSession) {
                uploadService.complete(uploadId);
            }
        }
    }

    /** 放弃上传（前端点"取消"或换文件时） */
    @DeleteMapping("/{uploadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@PathVariable String uploadId) {
        uploadService.abort(uploadId);
    }

    /** 初始化请求体：只需要文件名与总大小，用于计算分片数 */
    @Data
    public static class InitRequest {
        private String fileName;
        /** 声明大小仅用于算分片数，实际落盘大小以合并结果为准 */
        private long fileSize;
    }

    /** 合并入库请求体；title/author 为空时从 PDF 元数据推断 */
    @Data
    public static class CompleteRequest {
        private String title;
        private String author;
        /** 兼容 confirm_duplicate / confirmDuplicate 两种写法 */
        @JsonAlias("confirm_duplicate")
        private Boolean confirmDuplicate;
    }
}
