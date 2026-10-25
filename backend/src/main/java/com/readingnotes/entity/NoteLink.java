package com.readingnotes.entity;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 笔记双向链接实体（数据库设计 §5.7 note_links 表 / API 文档 §9.10）
 *
 * <p>一条记录表示 source 笔记指向 target 笔记的单向引用；查询时按视角拆分为
 * outgoing（source=当前笔记）与 incoming（target=当前笔记），实现"双向"。
 * targetTitle/sourceTitle 非表列，为查询时 join notes 填充的展示字段。</p>
 *
 * <p>序列化统一 snake_case（对齐 API 文档 §9.10 与前端读取约定）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class NoteLink {

    /** 主键，格式：link_ + 时间戳 + 随机后缀 */
    private String linkId;

    /** 来源笔记（出链方） */
    private String sourceNoteId;

    /** 目标笔记（入链方） */
    private String targetNoteId;

    /** 链接上下文（可空） */
    private String context;

    /** 目标是否可跳转：0=目标被永久删除后置灰（purge 联动置 0） */
    private Integer targetJumpable;

    /** 创建时间（ISO 毫秒格式） */
    private String createdAt;

    /** 目标笔记标题（join notes 填充；目标已删/不存在为 null → 前端显示"已删除笔记（链接失效）"） */
    private String targetTitle;

    /** 来源笔记标题（incoming 展示用，join notes 填充） */
    private String sourceTitle;
}
