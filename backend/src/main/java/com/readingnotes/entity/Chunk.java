package com.readingnotes.entity;

import lombok.Data;

/**
 * 通用切片（数据库设计 §5.3 chunks 表）
 * 原书切片 chunk_type='book'，回流笔记 chunk_type='note'，靠类型区分。
 */
@Data
public class Chunk {

    private String chunkId;
    private String bookId;
    private String noteId;
    /** book / note */
    private String chunkType;
    private String content;
    private Integer page;
    private String chapterPath;
    private String chapterTitle;
    private Integer position;
    private Integer wordCount;
    /** PENDING / DONE / FAILED */
    private String embeddingStatus;
    private Integer embeddingBatchId;
    private String vectorId;
    private String createdAt;
}
