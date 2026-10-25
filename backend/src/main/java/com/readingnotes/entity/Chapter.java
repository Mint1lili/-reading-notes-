package com.readingnotes.entity;

import lombok.Data;

/**
 * 章节结构（数据库设计 §5.2 chapters 表）。
 * <p>
 * 一本书的章节以扁平列表存储，层级关系由 {@code chapterPath} 的编号深度表达
 * （如 1 → 1.1 → 1.1.1），前端按 path 前缀重建树，避免维护 parent_id。
 */
@Data
public class Chapter {

    private String chapterId;
    private String bookId;

    /** 章节路径，如 "1" / "1.2" / "序言"；同时作为切片归属与问答过滤的依据 */
    private String chapterPath;

    private String title;

    /** 1~3，由编号深度决定，字号只作兜底修正 */
    private Integer level;

    /** 起始页（含），从 1 开始 */
    private Integer startPage;

    /** 结束页（含）；等于下一章的 startPage - 1 */
    private Integer endPage;

    /** 正文首字符在全文中的偏移，用于把切片精确定位回章节 */
    private Integer textOffset;

    /** 正文去空白后的字数，用于章节长度统计与切片密度检查 */
    private Integer wordCount;
}
