package com.readingnotes.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 时间工具：统一时间字符串格式（ISO-8601 UTC，与 API 文档示例一致）。
 * <p>
 * 数据库时间列统一 VARCHAR(32)，格式 {@code 2026-10-15T08:00:00.000Z}，
 * 这样字符串比较即可用于 purge_at 过期判断，且前端可直接 new Date() 解析。
 */
public final class Times {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private Times() {
    }

    /** 当前时间（UTC，ISO 格式） */
    public static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    /** 指定时刻之后 n 天（UTC，ISO 格式） */
    public static String plusDays(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).format(ISO);
    }

    /**
     * 解析数据库中的时间字符串，兼容：
     * ISO（带 Z）、ISO 无 Z、{@code yyyy-MM-dd HH:mm:ss}、纯日期。
     * 解析失败返回 null，调用方自行兜底。
     */
    public static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            if (t.endsWith("Z")) {
                return OffsetDateTime.parse(t).toLocalDateTime();
            }
            if (t.length() >= 19 && t.charAt(10) == 'T') {
                return LocalDateTime.parse(t.substring(0, 19));
            }
            if (t.length() >= 19 && t.charAt(10) == ' ') {
                return LocalDateTime.parse(t.substring(0, 19).replace(' ', 'T'));
            }
            if (t.length() >= 10) {
                return LocalDate.parse(t.substring(0, 10)).atStartOfDay();
            }
        } catch (Exception ignored) {
            // 落到下面返回 null
        }
        return null;
    }
}
