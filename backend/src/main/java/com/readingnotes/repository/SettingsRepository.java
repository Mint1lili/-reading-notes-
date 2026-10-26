package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 系统设置键值表数据访问层
 * 表：settings（见 Migration 0005）
 * 用于持久化模型参数 / RAG 参数，避免服务重启后热更新丢失
 */
@Slf4j
@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取所有设置项 */
    public List<SettingsKV> findAll() {
        return jdbc.query("SELECT setting_key, setting_value FROM settings",
                (rs, i) -> {
                    SettingsKV kv = new SettingsKV();
                    kv.key = rs.getString("setting_key");
                    kv.value = rs.getString("setting_value");
                    return kv;
                });
    }

    /** 单个键值 upsert（先 update，affected=0 则 insert，兼容 MySQL/SQLite） */
    public void upsert(String key, String value, String description) {
        String now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
        int affected = jdbc.update("""
                UPDATE settings
                   SET setting_value = ?, description = ?, updated_at = ?
                 WHERE setting_key = ?
                """, value, description, now, key);
        if (affected == 0) {
            jdbc.update("""
                    INSERT INTO settings (setting_key, setting_value, description, updated_at)
                    VALUES (?, ?, ?, ?)
                    """, key, value, description, now);
        }
    }

    /** 单键查询 */
    public String find(String key) {
        List<String> list = jdbc.queryForList(
                "SELECT setting_value FROM settings WHERE setting_key = ?", String.class, key);
        return list.isEmpty() ? null : list.get(0);
    }

    public static class SettingsKV {
        public String key;
        public String value;
    }
}
