package com.readingnotes.entity;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 书籍实体（数据库设计 §5.1 books 表）
 *
 * <p>序列化统一 snake_case（对齐 API 文档 §9 与前端读取约定，如 book_id）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Book {

    private String bookId;
    private String title;
    private String author;
    private String filePath;
    private Long fileSize;
    private Integer totalPages;

    /** UPLOADING/BUILDING/READY/REBUILDING/DELETING/DELETED/PURGED/CANCELLED/FAILED */
    private String status;

    /** PARSE/RECOGNIZE/CHUNK/EMBED/INGEST */
    private String buildSubStage;
    private Integer buildProgress;

    private String errorCode;
    private String errorMessage;

    private Integer chunkCount;
    private Double chapterRecognizedRate;
    private String embeddingModel;
    private Integer embeddingDim;

    private String createdAt;
    private String updatedAt;
    private String lastReadyAt;

    private Integer softDelete;
    private String deletedAt;
    private String purgeAt;
}
