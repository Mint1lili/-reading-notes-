package com.readingnotes.entity;

import lombok.Data;

/**
 * 笔记实体（数据库设计 §5.4 notes 表 / API 文档 §9）
 *
 * <p>第一阶段（CRUD）只使用核心字段：note_id/title/content/source_type/
 * word_count/editor_version/soft_delete/deleted_at/created_at/updated_at；
 * 回流相关字段（source_book_id/source_chunk_id/source_ai_version_id/
 * is_indexed/index_status/last_index_version）由阶段 5（AI 回流）写入。</p>
 */
@Data
public class Note {

    /** 主键，格式：note_ + 时间戳 + 随机后缀（文档示例 note_2026xxx） */
    private String noteId;

    /** 标题（可空，前端可后续编辑补齐） */
    private String title;

    /** Markdown 正文（必填，NOT NULL） */
    private String content;

    /** 来源类型：MANUAL / AI_SUMMARY / AI_QA / AI_MINDMAP / BOOK_EXCERPT，默认 MANUAL */
    private String sourceType;

    /** 来源书（阶段 5 写入，用于知识回流与溯源跳转） */
    private String sourceBookId;

    /** 来源页码 */
    private Integer sourcePage;

    /** 来源切片 */
    private String sourceChunkId;

    /** 来源 AI 产物版本 */
    private String sourceAiVersionId;

    /** 所属书（前端工作台语境，可空） */
    private String bookOwnerId;

    /** 是否已进向量库：0 否 / 1 是（阶段 5 回流置 1） */
    private Integer isIndexed;

    /** 回流状态：NOT_INDEXED / INDEXING / INDEXED / INDEX_FAILED */
    private String indexStatus;

    /** 最近一次回流写入的向量版本 */
    private String lastIndexVersion;

    /** 字数（去空白字符数，创建/编辑时计算） */
    private Integer wordCount;

    /** 版本号，乐观锁：每次编辑 +1，编辑请求必须携带当前版本 */
    private Integer editorVersion;

    /** 软删标记：0 正常 / 1 已删（回收站） */
    private Integer softDelete;

    /** 软删时间 */
    private String deletedAt;

    /** 创建时间（ISO 毫秒格式，如 2026-10-16T10:00:00.123Z） */
    private String createdAt;

    /** 更新时间 */
    private String updatedAt;
}
