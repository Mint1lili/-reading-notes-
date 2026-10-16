package com.readingnotes.repository;

import com.readingnotes.entity.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 标签数据访问层（JdbcTemplate + MySQL）
 *
 * <p>第二阶段（F4 标签）实现：按名查 id / 创建 / 幂等绑定(note_tag_map) /
 * 标签列表(含笔记数) / 按笔记查标签 / 批量按笔记查标签（列表页避免 N+1）。
 * 表结构见 0001_init.sql §6（tags + note_tag_map），种子数据见 0004_seed_tags.sql。</p>
 */
@Slf4j
@Repository
public class TagRepository {

    private final JdbcTemplate jdbc;

    public TagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 通用行映射：尝试读 note_count 列（listWithCount 有、其余查询没有，缺列时忽略） */
    private static final RowMapper<Tag> MAPPER = (rs, i) -> {
        Tag t = new Tag();
        t.setTagId(rs.getString("tag_id"));
        t.setName(rs.getString("name"));
        t.setColor(rs.getString("color"));
        t.setCreatedAt(rs.getString("created_at"));
        try {
            t.setNoteCount(rs.getInt("note_count"));
        } catch (SQLException ignored) {
            // 无 note_count 列的查询不设置
        }
        return t;
    };

    // ==================== 查询 ====================

    /** 按标签名查 tag_id（不存在返回 null） */
    public String findIdByName(String name) {
        List<String> ids = jdbc.query(
                "SELECT tag_id FROM tags WHERE name = ?",
                (rs, i) -> rs.getString(1), name);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 标签列表（含各标签笔记数 note_count，按创建时间升序，种子标签排前） */
    public List<Tag> listWithCount() {
        return jdbc.query("""
                SELECT t.tag_id, t.name, t.color, t.created_at,
                       COUNT(m.note_id) AS note_count
                  FROM tags t
                  LEFT JOIN note_tag_map m ON m.tag_id = t.tag_id
                 GROUP BY t.tag_id, t.name, t.color, t.created_at
                 ORDER BY t.created_at ASC
                """, MAPPER);
    }

    /** 某笔记的标签列表（详情页） */
    public List<Tag> findByNoteId(String noteId) {
        return jdbc.query("""
                SELECT t.tag_id, t.name, t.color, t.created_at
                  FROM tags t
                  JOIN note_tag_map m ON m.tag_id = t.tag_id
                 WHERE m.note_id = ?
                 ORDER BY t.created_at ASC
                """, MAPPER, noteId);
    }

    /**
     * 批量查多个笔记的标签（列表页避免 N+1 查询）
     *
     * @return note_id -> 该笔记的标签列表（无标签的笔记不出现或为空列表）
     */
    public Map<String, List<Tag>> findByNoteIds(Collection<String> noteIds) {
        Map<String, List<Tag>> result = new HashMap<>();
        if (noteIds == null || noteIds.isEmpty()) {
            return result;
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(noteIds));
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("""
                SELECT m.note_id, t.tag_id, t.name, t.color, t.created_at
                  FROM note_tag_map m
                  JOIN tags t ON t.tag_id = m.tag_id
                 WHERE m.note_id IN (%s)
                 ORDER BY t.created_at ASC
                """.formatted(in), rs -> {
            String nid = rs.getString("note_id");
            Tag t = new Tag();
            t.setTagId(rs.getString("tag_id"));
            t.setName(rs.getString("name"));
            t.setColor(rs.getString("color"));
            t.setCreatedAt(rs.getString("created_at"));
            result.computeIfAbsent(nid, k -> new ArrayList<>()).add(t);
        }, ids.toArray());
        return result;
    }

    // ==================== 写入 ====================

    /** 创建标签（name 唯一性由 Service 判定，此处直接插入） */
    public void insert(Tag t) {
        jdbc.update(
                "INSERT INTO tags (tag_id, name, color, created_at) VALUES (?, ?, ?, ?)",
                t.getTagId(), t.getName(), t.getColor(), t.getCreatedAt());
    }

    /**
     * 绑定笔记-标签（INSERT IGNORE 幂等：复合主键已存在时静默跳过）
     */
    public void attach(String noteId, String tagId) {
        jdbc.update(
                "INSERT IGNORE INTO note_tag_map (note_id, tag_id, created_at) VALUES (?, ?, ?)",
                noteId, tagId, NoteRepository.nowIso());
    }
}
