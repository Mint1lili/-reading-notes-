package com.readingnotes.service;

import com.readingnotes.entity.Book;
import com.readingnotes.entity.Chunk;

import java.util.List;
import java.util.Map;

/**
 * 知识库构建服务（F2 查询类接口，API 文档 §6）
 */
public interface BuildService {

    /** 构建状态（6.1） */
    Map<String, Object> status(String bookId);

    /** 重试构建（6.2，断点续传） */
    Map<String, Object> retry(String bookId, Integer fromBatch);

    /** 取消构建（6.3） */
    Map<String, Object> cancel(String bookId);

    /** 切片预览（6.4） */
    Map<String, Object> chunks(String bookId, int page, int pageSize, String chapter);

    /** 章节结构（6.5） */
    Map<String, Object> chapters(String bookId);

    /** 切片预览统计（构建前展示：切片数/字数分布/章节识别率） */
    Map<String, Object> previewStats(Book book);
}
