package com.readingnotes.entity;

import lombok.Data;

/**
 * 通用切片（数据库设计 §5.3 chunks 表）。
 * <p>
 * 原书切片 {@code chunkType='book'}，回流笔记 {@code chunkType='note'}，
 * 两类数据共用一张表与同一套向量检索逻辑，靠类型字段区分。
 */
@Data
public class Chunk {

    /** 由 bookId/noteId + position 派生的稳定 id，重建时同位置复用，便于断点续传去重 */
    private String chunkId;

    private String bookId;

    /** 笔记回流切片才有值，原书切片为 null */
    private String noteId;

    /** book / note */
    private String chunkType;

    /** 切片正文，含与上一片的重叠部分 */
    private String content;

    /** 切片起始页（1 起）；切片跨页时取起始字符所在页 */
    private Integer page;

    /** 所属章节路径与标题，冗余存储以避免检索时回表关联 chapters */
    private String chapterPath;
    private String chapterTitle;

    /** 全书内序号，从 0 开始，决定拼接顺序与 chunkId */
    private Integer position;

    /** 正文去空白字数，用于批次规划与质量统计 */
    private Integer wordCount;

    /** PENDING / DONE / FAILED */
    private String embeddingStatus;

    /** 所属批次号（每批 embeddingBatchSize 条），断点续传按批次跳过 */
    private Integer embeddingBatchId;

    /** Chroma 中的向量 id，通常与 chunkId 相同；删除集合时无需逐条清理 */
    private String vectorId;

    private String createdAt;
}
