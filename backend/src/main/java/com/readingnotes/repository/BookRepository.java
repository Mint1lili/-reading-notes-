package com.readingnotes.repository;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍数据访问层（JdbcTemplate，MySQL 8）。
 * <p>
 * 覆盖 F1 书籍管理所需的全部读写与状态机操作。约定：
 * <ul>
 *   <li>{@code list*} 查询默认排除软删（{@code soft_delete = 0}）</li>
 *   <li>{@code findOrNull} 不抛异常，供状态机内部判断；{@code findById/findActive} 抛 20005，供接口层使用</li>
 *   <li>所有时间参数由调用方以 ISO 字符串传入，本层不生成时间，便于测试注入</li>
 * </ul>
 */
@Slf4j
@Repository
public class BookRepository {

    private final JdbcTemplate jdbc;

    public BookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 防御式读取：可空数值列用基础 getter + wasNull 手动判空（MySQL/SQLite 均适用）
    private static Integer getInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<Book> MAPPER = (rs, i) -> {
        Book b = new Book();
        b.setBookId(rs.getString("book_id"));
        b.setOwnerId(getLong(rs, "owner_id"));
        b.setTitle(rs.getString("title"));
        b.setAuthor(rs.getString("author"));
        b.setFilePath(rs.getString("file_path"));
        b.setFileSize(getLong(rs, "file_size"));
        b.setTotalPages(getInt(rs, "total_pages"));
        b.setStatus(rs.getString("status"));
        b.setBuildSubStage(rs.getString("build_sub_stage"));
        b.setBuildProgress(rs.getInt("build_progress"));
        b.setErrorCode(rs.getString("error_code"));
        b.setErrorMessage(rs.getString("error_message"));
        b.setChunkCount(getInt(rs, "chunk_count"));
        b.setChapterRecognizedRate(getDouble(rs, "chapter_recognized_rate"));
        b.setEmbeddingModel(rs.getString("embedding_model"));
        b.setEmbeddingDim(getInt(rs, "embedding_dim"));
        b.setCreatedAt(rs.getString("created_at"));
        b.setUpdatedAt(rs.getString("updated_at"));
        b.setLastReadyAt(rs.getString("last_ready_at"));
        b.setSoftDelete(rs.getInt("soft_delete"));
        b.setDeletedAt(rs.getString("deleted_at"));
        b.setPurgeAt(rs.getString("purge_at"));
        return b;
    };

    /**
     * 书籍列表（默认不含软删；支持 status/keyword 过滤；多租户：可见=全局共享(owner_id IS NULL) 或 本人上传）。
     *
     * @param ownerId 当前登录用户 id（AuthContext 解析），必传非空
     */
    public List<Book> list(String status, String keyword, Long ownerId, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM books WHERE soft_delete = 0 AND (owner_id IS NULL OR owner_id = ?)");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR author LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 维护任务专用：查看全部书（含私有书），不做租户过滤。
     * 仅供启动恢复/定时清理等系统级任务使用，禁止用于对外查询。
     */
    public List<Book> listAll(String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT * FROM books WHERE soft_delete = 0");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String status, String keyword, Long ownerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM books WHERE soft_delete = 0 AND (owner_id IS NULL OR owner_id = ?)");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR author LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        Long c = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return c == null ? 0 : c;
    }

    /** 按 id 查（含软删），查不到返回 null —— 不做抛异常，供状态机内部判断 */
    public Book findOrNull(String bookId) {
        List<Book> list = jdbc.query("SELECT * FROM books WHERE book_id = ?", MAPPER, bookId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 id 查（含软删），查不到抛 20005 —— 供接口层使用 */
    public Book findById(String bookId) {
        Book b = findOrNull(bookId);
        if (b == null) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return b;
    }

    /** 按 id 查未删除的书，查不到抛 20005 */
    public Book findActive(String bookId) {
        Book b = findById(bookId);
        if (b.getSoftDelete() != null && b.getSoftDelete() == 1) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return b;
    }

    /** 同名书籍（未删除、排除自身），用于上传重名确认 */
    public Book findActiveByTitle(String title) {
        List<Book> list = jdbc.query(
                "SELECT * FROM books WHERE soft_delete = 0 AND status <> 'PURGED' AND title = ? LIMIT 1",
                MAPPER, title);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(Book b) {
        jdbc.update("""
                INSERT INTO books (book_id, owner_id, title, author, file_path, file_size, total_pages,
                                   status, build_sub_stage, build_progress,
                                   error_code, error_message, chunk_count, chapter_recognized_rate,
                                   embedding_model, embedding_dim, created_at, updated_at,
                                   last_ready_at, soft_delete, deleted_at, purge_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                b.getBookId(), b.getOwnerId(), b.getTitle(), b.getAuthor(), b.getFilePath(), b.getFileSize(), b.getTotalPages(),
                b.getStatus(), b.getBuildSubStage(), b.getBuildProgress(),
                b.getErrorCode(), b.getErrorMessage(), b.getChunkCount(), b.getChapterRecognizedRate(),
                b.getEmbeddingModel(), b.getEmbeddingDim(), b.getCreatedAt(), b.getUpdatedAt(),
                b.getLastReadyAt(), b.getSoftDelete(), b.getDeletedAt(), b.getPurgeAt());
    }

    // =========================================================================
    // 状态机与进度（F1/F2）
    // =========================================================================

    /** 更新状态 + 子阶段 + 进度，并清空错误信息 */
    public void updateStage(String bookId, String status, String subStage, int progress, String now) {
        jdbc.update("""
                UPDATE books SET status = ?, build_sub_stage = ?, build_progress = ?,
                                 error_code = NULL, error_message = NULL, updated_at = ?
                WHERE book_id = ?
                """, status, subStage, progress, now, bookId);
    }

    /** 仅更新进度（子阶段不变），用于向量化批次推进 */
    public void updateProgress(String bookId, int progress, String now) {
        jdbc.update("UPDATE books SET build_progress = ?, updated_at = ? WHERE book_id = ?",
                progress, now, bookId);
    }

    /** 构建成功：READY + 100% + 切片数/识别率/模型信息 */
    public void markReady(String bookId, int chunkCount, double chapterRate,
                          String embeddingModel, int embeddingDim, int totalPages, String now) {
        jdbc.update("""
                UPDATE books SET status = 'READY', build_sub_stage = NULL, build_progress = 100,
                                 chunk_count = ?, chapter_recognized_rate = ?, embedding_model = ?,
                                 embedding_dim = ?, total_pages = ?, last_ready_at = ?,
                                 error_code = NULL, error_message = NULL, updated_at = ?
                WHERE book_id = ?
                """, chunkCount, chapterRate, embeddingModel, embeddingDim, totalPages, now, now, bookId);
    }

    /** 构建失败：FAILED + 错误码（30001 扫描件 / 30002 超时 / 30004 重试仍失败 / 70001 Chroma） */
    public void markFailed(String bookId, String errorCode, String errorMessage, String now) {
        jdbc.update("""
                UPDATE books SET status = 'FAILED', error_code = ?, error_message = ?, updated_at = ?
                WHERE book_id = ?
                """, errorCode, truncate(errorMessage, 1000), now, bookId);
    }

    /** 取消构建 */
    public void markCancelled(String bookId, String now) {
        jdbc.update("""
                UPDATE books SET status = 'CANCELLED', build_sub_stage = NULL, updated_at = ?
                WHERE book_id = ?
                """, now, bookId);
    }

    /** 编辑书名/作者 */
    public void updateMeta(String bookId, String title, String author, String now) {
        jdbc.update("UPDATE books SET title = ?, author = ?, updated_at = ? WHERE book_id = ?",
                title, author, now, bookId);
    }

    /** 软删除：进回收站，记录 purge_at */
    public void softDelete(String bookId, String deletedAt, String purgeAt, String now) {
        jdbc.update("""
                UPDATE books SET status = 'DELETED', soft_delete = 1,
                                 deleted_at = ?, purge_at = ?, updated_at = ?
                WHERE book_id = ?
                """, deletedAt, purgeAt, now, bookId);
    }

    /** 恢复：从回收站还原 */
    public void restore(String bookId, String status, String now) {
        jdbc.update("""
                UPDATE books SET status = ?, soft_delete = 0, deleted_at = NULL, purge_at = NULL,
                                 error_code = NULL, error_message = NULL, updated_at = ?
                WHERE book_id = ?
                """, status, now, bookId);
    }

    /** 永久删除前置标记 */
    public void markPurging(String bookId, String now) {
        jdbc.update("UPDATE books SET status = 'PURGED', updated_at = ? WHERE book_id = ?", now, bookId);
    }

    /** 硬删除记录 */
    public void hardDelete(String bookId) {
        jdbc.update("DELETE FROM books WHERE book_id = ?", bookId);
    }

    /** 回收站列表：已软删且仍在保留期内 */
    public List<Book> listTrash() {
        return jdbc.query("""
                SELECT * FROM books WHERE soft_delete = 1 AND status IN ('DELETED','DELETING','FAILED')
                ORDER BY deleted_at DESC
                """, MAPPER);
    }

    /** 已过期（purge_at <= now）的书，供定时清理任务使用 */
    public List<Book> listExpired(String now) {
        return jdbc.query("""
                SELECT * FROM books WHERE soft_delete = 1 AND purge_at IS NOT NULL AND purge_at <= ?
                """, MAPPER, now);
    }

    /** 关联笔记数（软删笔记不计），列表页 note_count 字段 */
    public int countNotes(String bookId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notes WHERE source_book_id = ? AND soft_delete = 0",
                Integer.class, bookId);
        return n == null ? 0 : n;
    }

    /** 批量关联笔记数：一次 GROUP BY 聚合，避免列表页 N+1 查询 */
    public Map<String, Integer> countNotesBatch(List<String> bookIds) {
        Map<String, Integer> map = new HashMap<>();
        if (bookIds == null || bookIds.isEmpty()) {
            return map;
        }
        String in = String.join(",", Collections.nCopies(bookIds.size(), "?"));
        jdbc.query("SELECT source_book_id, COUNT(*) c FROM notes "
                        + "WHERE soft_delete = 0 AND source_book_id IN (" + in + ") GROUP BY source_book_id",
                rs -> {
                    map.put(rs.getString(1), rs.getInt(2));
                }, bookIds.toArray());
        return map;
    }

    /** 取出所有 READY 书籍的 id（跨书问答用，B 成员消费） */
    public List<String> listReadyIds(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return jdbc.query("SELECT book_id FROM books WHERE status = 'READY' AND soft_delete = 0",
                    (rs, i) -> rs.getString(1));
        }
        String in = String.join(",", Collections.nCopies(bookIds.size(), "?"));
        return jdbc.query("SELECT book_id FROM books WHERE status = 'READY' AND soft_delete = 0 AND book_id IN ("
                        + in + ")", (rs, i) -> rs.getString(1), bookIds.toArray());
    }

    /**
     * 计算向量化阶段的进度百分比：30% → 90% 按批次线性推进。
     * <p>
     * 30% 之前留给解析/章节识别/切片，90% 之后留给入库校验，进度条因此不会长时间停在 0 或 99。
     *
     * @param batchIndex   已完成批次数
     * @param totalBatches 总批次数；为 0 时返回起点 30
     * @return 30~90 之间的整数百分比
     */
    public static int embedProgress(int batchIndex, int totalBatches) {
        if (totalBatches <= 0) {
            return 30;
        }
        double ratio = (double) batchIndex / totalBatches;
        return (int) Math.min(90, 30 + Math.floor(ratio * 60));
    }

    /** 截断错误信息，避免超长堆栈写爆 VARCHAR(1000) 列 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
