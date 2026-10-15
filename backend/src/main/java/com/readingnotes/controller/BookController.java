package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.entity.Book;
import com.readingnotes.service.BookService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍管理接口（API 文档 §5）
 * 脚手架已实现：列表 GET /books、详情 GET /books/{book_id}（全链路示例）
 * 待扩展（F1 业务）：
 * - POST /books/upload          上传 PDF（multipart）
 * - PATCH /books/{book_id}      编辑书名/作者
 * - DELETE /books/{book_id}     软删除（级联事务）
 * - POST /books/{book_id}/restore / DELETE /books/{book_id}/purge / GET /books/trash
 * - POST /books/{book_id}/rebuild
 */
@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<Book> items = bookService.list(status, keyword, page, pageSize);
        Map<String, Object> data = new HashMap<>();
        data.put("total", bookService.count(status, keyword));
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        return Result.ok(data);
    }

    @GetMapping("/{bookId}")
    public Result<Book> detail(@PathVariable String bookId) {
        return Result.ok(bookService.detail(bookId));
    }
}
