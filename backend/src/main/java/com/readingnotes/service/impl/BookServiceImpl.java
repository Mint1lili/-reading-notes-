package com.readingnotes.service.impl;

import com.readingnotes.entity.Book;
import com.readingnotes.repository.BookRepository;
import com.readingnotes.service.BookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 书籍服务实现（F1）
 * 脚手架阶段提供列表/详情；上传、删除事务、回收站按需求 F1.1/F1.2 在此扩展。
 */
@Slf4j
@Service
public class BookServiceImpl implements BookService {

    private final BookRepository bookRepository;

    public BookServiceImpl(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Override
    public List<Book> list(String status, String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return bookRepository.list(status, keyword, safePage, safeSize);
    }

    @Override
    public long count(String status, String keyword) {
        return bookRepository.count(status, keyword);
    }

    @Override
    public Book detail(String bookId) {
        return bookRepository.findById(bookId);
    }
}
