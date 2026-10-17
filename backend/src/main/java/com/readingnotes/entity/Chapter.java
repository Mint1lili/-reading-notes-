package com.readingnotes.entity;

import lombok.Data;

/**
 * 章节结构（数据库设计 §5.2 chapters 表）
 */
@Data
public class Chapter {

    private String chapterId;
    private String bookId;
    /** 如 "1" / "1.2" / "序言" */
    private String chapterPath;
    private String title;
    /** 1~3 */
    private Integer level;
    private Integer startPage;
    private Integer endPage;
    /** 正文首字符在全文中的偏移 */
    private Integer textOffset;
    private Integer wordCount;
}
