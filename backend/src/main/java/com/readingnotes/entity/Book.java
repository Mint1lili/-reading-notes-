package com.readingnotes.entity;

import lombok.Data;

/**
 * 书籍实体（数据库设计 §5.1 books 表）。
 * <p>
 * 一行代表一本原书，同时承载"上传 → 构建 → 就绪"的全生命周期状态；
 * 软删除通过 {@code softDelete + deletedAt + purgeAt} 三个字段表达，物理行保留 7 天供恢复。
 */
@Data
public class Book {

    /** 业务主键，格式 {@code book_<32位hex>} */
    private String bookId;

    /** 书名，默认取 PDF 元数据标题，允许用户编辑 */
    private String title;

    private String author;

    /** 原书 PDF 在 uploads 目录下的绝对路径；恢复时据此重新解析 */
    private String filePath;

    /** 文件字节数，用于 50MB 上限校验与展示 */
    private Long fileSize;

    /** PDF 页数，解析阶段回填 */
    private Integer totalPages;

    /** UPLOADING/BUILDING/READY/REBUILDING/DELETING/DELETED/PURGED/CANCELLED/FAILED */
    private String status;

    /** PARSE/RECOGNIZE/CHUNK/EMBED/INGEST */
    private String buildSubStage;

    /** 构建总进度 0~100，仅用于前端进度条，不参与断点判断 */
    private Integer buildProgress;

    /** 失败时的错误码（对应 ErrorCode），成功时为 null */
    private String errorCode;

    /** 面向用户的失败原因，可直接展示 */
    private String errorMessage;

    /** 已成功写入向量库的切片数；与 chunk_count_planned 比对可发现残缺构建 */
    private Integer chunkCount;

    /** 章节识别率 0~1，低于阈值时问答退化为纯向量检索（F2.3） */
    private Double chapterRecognizedRate;

    /** 构建时实际使用的向量模型与维度，换模型后需重建才能检索 */
    private String embeddingModel;
    private Integer embeddingDim;

    private String createdAt;
    private String updatedAt;

    /** 最近一次构建成功（READY）的时间 */
    private String lastReadyAt;

    /** 0=正常，1=已进回收站；用 Integer 而非 boolean 是为了兼容 MySQL tinyint 与 null */
    private Integer softDelete;

    /** 进入回收站的时间 */
    private String deletedAt;

    /** 计划物理清除的时间（deletedAt + 7 天），定时任务按此字段扫描 */
    private String purgeAt;
}
