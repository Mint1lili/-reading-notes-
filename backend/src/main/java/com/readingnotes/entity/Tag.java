package com.readingnotes.entity;

import lombok.Data;

/**
 * 标签实体（数据库设计 §5.5 tags 表 / API 文档 §9.12）
 *
 * <p>noteCount 不是 tags 表列，仅供 GET /tags 列表展示该标签下的笔记数使用。</p>
 */
@Data
public class Tag {

    /** 主键，格式：tag_ + 时间戳 + 随机后缀（种子标签为 tag_high_freq 等语义化 id） */
    private String tagId;

    /** 标签名（唯一索引 idx_tags_name） */
    private String name;

    /** 显示色（hex，可空） */
    private String color;

    /** 创建时间（ISO 毫秒格式） */
    private String createdAt;

    /** 该标签下的笔记数（GET /tags 列表用，非表列） */
    private Integer noteCount;
}
