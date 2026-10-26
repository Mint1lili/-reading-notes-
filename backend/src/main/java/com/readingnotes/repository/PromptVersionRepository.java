package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 模板多版本数据访问层
 * 表：prompt_versions（见 Migration 0001 §14）
 */
@Slf4j
@Repository
public class PromptVersionRepository {

    private final JdbcTemplate jdbc;

    public PromptVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 数据库实体（仅 Repository 内部使用） */
    public static class PromptVersion {
        public String name;
        public int version;
        public String content;
        public int isActive;
        public String author;
        public String description;
        public String createdAt;
    }

    private static final RowMapper<PromptVersion> MAPPER = (rs, i) -> {
        PromptVersion v = new PromptVersion();
        v.name = rs.getString("name");
        v.version = rs.getInt("version");
        v.content = rs.getString("content");
        v.isActive = rs.getInt("is_active");
        v.author = rs.getString("author");
        v.description = rs.getString("description");
        v.createdAt = rs.getString("created_at");
        return v;
    };

    /** 查询所有唯一的 prompt name 列表（含活跃版本号、总版本数、更新时间） */
    public List<PromptSummary> listSummaries() {
        String sql = """
                SELECT pv.name,
                       MAX(CASE WHEN pv.is_active = 1 THEN pv.version END) AS active_version,
                       COUNT(*) AS total_versions,
                       MAX(pv.created_at) AS updated_at
                  FROM prompt_versions pv
                 GROUP BY pv.name
                 ORDER BY pv.name
                """;
        return jdbc.query(sql, (rs, i) -> {
            PromptSummary s = new PromptSummary();
            s.name = rs.getString("name");
            Integer active = rs.getObject("active_version") != null ? rs.getInt("active_version") : null;
            s.activeVersion = active;
            s.totalVersions = rs.getInt("total_versions");
            s.updatedAt = rs.getString("updated_at");
            return s;
        });
    }

    /** 按 name + version 查询（version=null 或 "latest"/"active" 取当前活跃版本） */
    public PromptVersion find(String name, Integer version, boolean activeOnly) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM prompt_versions WHERE name = ?");
        args.add(name);

        if (activeOnly) {
            sql.append(" AND is_active = 1");
        } else if (version != null) {
            sql.append(" AND version = ?");
            args.add(version);
        }

        sql.append(" ORDER BY version DESC LIMIT 1");
        List<PromptVersion> list = jdbc.query(sql.toString(), MAPPER, args.toArray());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询指定 name 的最大版本号 */
    public int maxVersion(String name) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM prompt_versions WHERE name = ?",
                Integer.class, name);
        return max == null ? 0 : max;
    }

    /** 查询指定 name 的版本总数 */
    public int countVersions(String name) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_versions WHERE name = ?",
                Integer.class, name);
        return c == null ? 0 : c;
    }

    /** 新增一个版本 */
    public void insert(PromptVersion v) {
        jdbc.update("""
                INSERT INTO prompt_versions (name, version, content, is_active, author, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                v.name, v.version, v.content, v.isActive, v.author, v.description, v.createdAt);
    }

    /** 将指定 name 的所有版本设为 inactive */
    public void deactivateAll(String name) {
        jdbc.update("UPDATE prompt_versions SET is_active = 0 WHERE name = ?", name);
    }

    /** 将指定 name + version 设为 active（同 name 其他版本自动 inactive 由唯一索引保证） */
    public void activate(String name, int version) {
        jdbc.update("UPDATE prompt_versions SET is_active = 0 WHERE name = ?", name);
        jdbc.update("UPDATE prompt_versions SET is_active = 1 WHERE name = ? AND version = ?", name, version);
    }

    /** 内部返回类型：Prompt 概要 */
    public static class PromptSummary {
        public String name;
        public Integer activeVersion;
        public int totalVersions;
        public String updatedAt;
    }
}
