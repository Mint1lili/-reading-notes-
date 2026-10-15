package com.readingnotes.service;

import com.readingnotes.entity.Book;

import java.util.List;

/**
 * 书籍服务（F1）——脚手架接口，实现见 BookServiceImpl
 */
public interface BookService {

    /** 书籍列表 */
    List<Book> list(String status, String keyword, int page, int pageSize);

    long count(String status, String keyword);

    /** 书籍详情 */
    Book detail(String bookId);
}
