package com.readingnotes.repository;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 笔记数据访问层（JdbcTemplate + MySQL）
 *
 * <p>第一阶段（F4 CRUD）实现：插入 / 详情 / 列表(分页+过滤) / 计数 /
 * 编辑(乐观锁) / 软删 / 恢复 / 永久删除 / 来源书存在性检查。
 * 写法与 {@link BookRepository} 完全一致，照抄其防御式读列模式。</p>
 */
@Slf4j
@Repository
public class NoteRepository {

    private final JdbcTemplate jdbc;

    public NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 列映射 ====================

    private static final RowMapper<Note> MAPPER = (rs, i) -> {
        Note n = new Note();
        n.setNoteId(rs.getString("note_id"));
        n.setTitle(rs.getString("title"));
        n.setContent(rs.getString("content"));
        n.setSourceType(rs.getString("source_type"));
        n.setSourceBookId(rs.getString("source_book_id"));
        n.setSourcePage(getInt(rs, "source_page"));
        n.setSourceChunkId(rs.getString("source_chunk_id"));
        n.setSourceAiVersionId(rs.getString("source_ai_version_id"));
        n.setBookOwnerId(rs.getString("book_owner_id"));
        n.setIsIndexed(rs.getInt("is_indexed"));
        n.setIndexStatus(rs.getString("index_status"));
        n.setLastIndexVersion(rs.getString("last_index_version"));
        n.setWordCount(getInt(rs, "word_count"));
        n.setEditorVersion(rs.getInt("editor_version"));
        n.setSoftDelete(rs.getInt("soft_delete"));
        n.setDeletedAt(rs.getString("deleted_at"));
        n.setCreatedAt(rs.getString("created_at"));
        n.setUpdatedAt(rs.getString("updated_at"));
        return n;
    };

    /** 防御式读取：可空数值列用基础 getter + wasNull 手动判空（MySQL/SQLite 均适用） */
    private static Integer getInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    // ==================== 写入 ====================

    /** 新建笔记：主键/时间/版本/软删标记由 Service 生成后整体写入 */
    public void insert(Note n) {
        jdbc.update("""
                INSERT INTO notes (note_id, title, content, source_type,
                                   source_book_id, source_page, source_chunk_id,
                                   source_ai_version_id, book_owner_id,
                                   is_indexed, index_status, last_index_version,
                                   word_count, editor_version, soft_delete,
                                   deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                n.getNoteId(), n.getTitle(), n.getContent(), n.getSourceType(),
                n.getSourceBookId(), n.getSourcePage(), n.getSourceChunkId(),
                n.getSourceAiVersionId(), n.getBookOwnerId(),
                n.getIsIndexed(), n.getIndexStatus(), n.getLastIndexVersion(),
                n.getWordCount(), n.getEditorVersion(), n.getSoftDelete(),
                n.getDeletedAt(), n.getCreatedAt(), n.getUpdatedAt());
    }

    // ==================== 查询 ====================

    /** 笔记详情（仅未软删；已删/不存在统一抛 NOTE_NOT_FOUND） */
    public Note findById(String noteId) {
        List<Note> list = jdbc.query(
                "SELECT * FROM notes WHERE note_id = ? AND soft_delete = 0",
                MAPPER, noteId);
        if (list.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        return list.get(0);
    }

    /**
     * 笔记列表（分页 + 可选过滤），仅未软删，按创建时间倒序。
     *
     * @param sourceBookId 按来源书过滤（可空）
     * @param sourceType   按来源类型过滤（可空）
     * @param keyword      标题/正文模糊搜索（可空）
     * @param tagName      按标签名过滤（可空，阶段 2：join note_tag_map）
     * @param indexed      按回流状态过滤（可空：true=已回流 is_indexed=1，false=未回流）
     */
    public List<Note> list(String sourceBookId, String sourceType, String keyword,
                           String tagName, Boolean indexed, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notes WHERE soft_delete = 0");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (sourceBookId != null && !sourceBookId.isBlank()) {
            sql.append(" AND source_book_id = ?");
            args.add(sourceBookId);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND source_type = ?");
            args.add(sourceType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR content LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (tagName != null && !tagName.isBlank()) {
            sql.append(" AND note_id IN (SELECT m.note_id FROM note_tag_map m"
                    + " JOIN tags t ON m.tag_id = t.tag_id WHERE t.name = ?)");
            args.add(tagName);
        }
        if (indexed != null) {
            sql.append(" AND is_indexed = ?");
            args.add(indexed ? 1 : 0);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 计数（与 list 同一套过滤条件，供分页返回 total） */
    public long count(String sourceBookId, String sourceType, String keyword,
                      String tagName, Boolean indexed) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM notes WHERE soft_delete = 0");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (sourceBookId != null && !sourceBookId.isBlank()) {
            sql.append(" AND source_book_id = ?");
            args.add(sourceBookId);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND source_type = ?");
            args.add(sourceType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR content LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (tagName != null && !tagName.isBlank()) {
            sql.append(" AND note_id IN (SELECT m.note_id FROM note_tag_map m"
                    + " JOIN tags t ON m.tag_id = t.tag_id WHERE t.name = ?)");
            args.add(tagName);
        }
        if (indexed != null) {
            sql.append(" AND is_indexed = ?");
            args.add(indexed ? 1 : 0);
        }
        Long c = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return c == null ? 0 : c;
    }

    /**
     * 按来源书查全部未软删笔记（导出 scope=book 用，API §9.11）
     * 按创建时间升序，便于导出后按阅读顺序阅读。
     */
    public List<Note> findByBookId(String bookId) {
        return jdbc.query(
                "SELECT * FROM notes WHERE source_book_id = ? AND soft_delete = 0"
                        + " ORDER BY source_page ASC, created_at ASC",
                MAPPER, bookId);
    }

    /** 记录是否存在（含软删，用于区分"不存在"与"版本冲突"） */
    public boolean exists(String noteId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notes WHERE note_id = ?", Long.class, noteId);
        return c != null && c > 0;
    }

    /** 来源书是否仍可用（can_jump_to_source 判定：书未软删即视为可跳转） */
    public boolean sourceBookExists(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return false;
        }
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM books WHERE book_id = ? AND soft_delete = 0",
                Long.class, bookId);
        return c != null && c > 0;
    }

    /** 来源书标题（列表响应展示用；书不存在/已删返回 null） */
    public String sourceBookTitle(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return null;
        }
        List<String> titles = jdbc.query(
                "SELECT title FROM books WHERE book_id = ? AND soft_delete = 0",
                (rs, i) -> rs.getString(1), bookId);
        return titles.isEmpty() ? null : titles.get(0);
    }

    // ==================== 编辑（乐观锁） ====================

    /**
     * 编辑笔记：WHERE 带 editor_version 实现乐观锁，仅当版本匹配才更新并把版本 +1。
     *
     * @return 更新行数（0 = 版本不匹配或笔记不存在，由 Service 区分处理）
     */
    public int updateContent(String noteId, String title, String content, int wordCount,
                             int expectedVersion) {
        return jdbc.update("""
                UPDATE notes
                   SET title = ?, content = ?, word_count = ?,
                       editor_version = editor_version + 1, updated_at = ?
                 WHERE note_id = ? AND soft_delete = 0 AND editor_version = ?
                """,
                title, content, wordCount, nowIso(), noteId, expectedVersion);
    }

    // ==================== 软删 / 恢复 / 永久删除 ====================

    /**
     * 软删除：soft_delete=1 + deleted_at。
     * 返回更新行数（0 = 不存在或已软删，Service 统一抛 NOTE_NOT_FOUND）。
     */
    public int softDelete(String noteId) {
        return jdbc.update("""
                UPDATE notes SET soft_delete = 1, deleted_at = ?
                 WHERE note_id = ? AND soft_delete = 0
                """, nowIso(), noteId);
    }

    /**
     * 恢复：soft_delete=0 + deleted_at 置空。
     * 返回更新行数（0 = 不存在；已正常未删的笔记视为幂等成功，由 Service 判定）。
     */
    public int restore(String noteId) {
        return jdbc.update("""
                UPDATE notes SET soft_delete = 0, deleted_at = NULL
                 WHERE note_id = ? AND soft_delete = 1
                """, noteId);
    }

    /**
     * 永久删除：硬删记录（含软删/未软删）。
     * 返回删除行数（0 = 不存在，抛 NOTE_NOT_FOUND）。
     * 注：必须先删 note_tag_map 关联（FK ON DELETE RESTRICT 会阻止直接删笔记）；
     * 双向链接记录按文档语义保留（target 侧置灰、source 侧留痕），其外键约束
     * 通过会话级 FOREIGN_KEY_CHECKS=0/1 包裹解除（try/finally 保证恢复）。
     * 两步删除在 Service 层包 @Transactional 保证原子。
     */
    public int purge(String noteId) {
        jdbc.update("DELETE FROM note_tag_map WHERE note_id = ?", noteId);
        jdbc.update("SET FOREIGN_KEY_CHECKS = 0");
        try {
            return jdbc.update("DELETE FROM notes WHERE note_id = ?", noteId);
        } finally {
            jdbc.update("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ==================== 工具 ====================

    /** 当前时间（ISO 毫秒，与种子数据格式一致，如 2026-10-16T10:00:00.123Z） */
    public static String nowIso() {
        return java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString();
    }
}
