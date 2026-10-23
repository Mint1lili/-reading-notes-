package com.readingnotes.util;

import java.util.UUID;

/**
 * 业务 id 生成器（数据库设计 §2.3：VARCHAR(64)，业务前缀 + 32 位 hex）。
 * <p>
 * 统一在这里生成而不是散落各处 {@code UUID.randomUUID()}，保证前缀规范一致，
 * 排查问题时从 id 就能看出实体类型。
 */
public final class Ids {

    private Ids() {
    }

    /** 书籍 id：{@code book_<32位hex>} */
    public static String bookId() {
        return "book_" + hex();
    }

    /** 章节 id：{@code ch_<32位hex>} */
    public static String chapterId() {
        return "ch_" + hex();
    }

    /** 32 位小写 hex（无连字符），也用于 trace_id 与 chunk_id 派生 */
    public static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
