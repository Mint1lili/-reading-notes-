package com.readingnotes.repository;

import com.readingnotes.entity.NoteLink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 笔记双向链接数据访问层（JdbcTemplate + MySQL）
 *
 * <p>第三阶段（F4 双向链接）实现：幂等创建 / 存在判定 / 出链查询(join 目标标题) /
 * 入链查询(join 来源标题) / 链接计数 / purge 联动（source 侧删除 + target 侧失效）。
 * 表结构见 0001_init.sql §7 note_links（link_id 主键 + target_jumpable 默认 1）。</p>
 */
@Slf4j
@Repository
public class NoteLinkRepository {

    private final JdbcTemplate jdbc;

    public NoteLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NoteLink> MAPPER = (rs, i) -> {
        NoteLink l = new NoteLink();
        l.setLinkId(rs.getString("link_id"));
        l.setSourceNoteId(rs.getString("source_note_id"));
        l.setTargetNoteId(rs.getString("target_note_id"));
        l.setContext(rs.getString("context"));
        l.setTargetJumpable(rs.getInt("target_jumpable"));
        l.setCreatedAt(rs.getString("created_at"));
        return l;
    };

    // ==================== 查询 ====================

    /** (source,target) 是否已有链接（幂等判定） */
    public boolean exists(String sourceNoteId, String targetNoteId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_links WHERE source_note_id = ? AND target_note_id = ?",
                Long.class, sourceNoteId, targetNoteId);
        return c != null && c > 0;
    }

    /**
     * 出链：source=当前笔记，LEFT JOIN 目标笔记标题（目标被删/软删则 title 为 null）。
     * target_jumpable 展示值由 Service 结合 title 判定（目标不存在时强制 false）。
     */
    public List<NoteLink> findOutgoing(String noteId) {
        return jdbc.query("""
                SELECT l.link_id, l.source_note_id, l.target_note_id, l.context,
                       l.target_jumpable, l.created_at,
                       n.title AS target_title
                  FROM note_links l
                  LEFT JOIN notes n ON n.note_id = l.target_note_id AND n.soft_delete = 0
                 WHERE l.source_note_id = ?
                 ORDER BY l.created_at ASC
                """, (rs, i) -> {
            NoteLink l = MAPPER.mapRow(rs, i);
            l.setTargetTitle(rs.getString("target_title"));
            return l;
        }, noteId);
    }

    /** 入链：target=当前笔记，LEFT JOIN 来源笔记标题 */
    public List<NoteLink> findIncoming(String noteId) {
        return jdbc.query("""
                SELECT l.link_id, l.source_note_id, l.target_note_id, l.context,
                       l.target_jumpable, l.created_at,
                       n.title AS source_title
                  FROM note_links l
                  LEFT JOIN notes n ON n.note_id = l.source_note_id AND n.soft_delete = 0
                 WHERE l.target_note_id = ?
                 ORDER BY l.created_at ASC
                """, (rs, i) -> {
            NoteLink l = MAPPER.mapRow(rs, i);
            l.setSourceTitle(rs.getString("source_title"));
            return l;
        }, noteId);
    }

    /** 该笔记的链接总数（出链 + 入链，列表 link_count 用） */
    public int countLinks(String noteId) {
        Long c = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM note_links WHERE source_note_id = ?)
                     + (SELECT COUNT(*) FROM note_links WHERE target_note_id = ?)
                """, Long.class, noteId, noteId);
        return c == null ? 0 : c.intValue();
    }

    /**
     * 批量查多个笔记的链接总数（列表页避免 N+1）
     *
     * @return note_id -> 出链+入链总数（无链接的笔记不出现或为 0）
     */
    public Map<String, Integer> countLinksByNoteIds(java.util.Collection<String> noteIds) {
        Map<String, Integer> result = new java.util.HashMap<>();
        if (noteIds == null || noteIds.isEmpty()) {
            return result;
        }
        java.util.List<String> ids = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(noteIds));
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        // 出链 + 入链 UNION 后按 note_id 汇总
        jdbc.query("""
                SELECT note_id, SUM(cnt) AS total FROM (
                    SELECT source_note_id AS note_id, COUNT(*) AS cnt
                      FROM note_links WHERE source_note_id IN (%s) GROUP BY source_note_id
                    UNION ALL
                    SELECT target_note_id AS note_id, COUNT(*) AS cnt
                      FROM note_links WHERE target_note_id IN (%s) GROUP BY target_note_id
                ) t GROUP BY note_id
                """.formatted(in, in), rs -> {
            result.put(rs.getString("note_id"), rs.getInt("total"));
        }, java.util.stream.Stream.concat(ids.stream(), ids.stream()).toArray());
        return result;
    }

    // ==================== 写入 ====================

    /** 创建链接（Service 层已判幂等，此处直接插入） */
    public void insert(NoteLink link) {
        jdbc.update("""
                INSERT INTO note_links (link_id, source_note_id, target_note_id,
                                        context, target_jumpable, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                link.getLinkId(), link.getSourceNoteId(), link.getTargetNoteId(),
                link.getContext(), 1, NoteRepository.nowIso());
    }

    /**
     * 永久删除笔记联动（文档 §9.7 第 4 步）：
     * source 侧链接直接删除（来源已不存在，记录无意义）；
     * target 侧链接置 target_jumpable=0（目标被永久删除后置灰，标题由查询兜底）。
     */
    public void purgeNoteLinks(String noteId) {
        jdbc.update("DELETE FROM note_links WHERE source_note_id = ?", noteId);
        jdbc.update("UPDATE note_links SET target_jumpable = 0 WHERE target_note_id = ?", noteId);
    }
}
