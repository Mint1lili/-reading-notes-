package com.readingnotes.util;

import java.util.UUID;

/**
 * 业务 id 生成器（数据库设计 §2.3：VARCHAR(64)，业务前缀 + 32 位 hex）
 */
public final class Ids {

    private Ids() {
    }

    public static String bookId() {
        return "book_" + hex();
    }

    public static String chapterId() {
        return "ch_" + hex();
    }

    /** 32 位小写 hex（无连字符） */
    public static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
