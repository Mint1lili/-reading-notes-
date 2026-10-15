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
import java.util.List;

/**
 * 书籍数据访问层（JdbcTemplate + SQLite）
 * 脚手架仅实现核心查询；写入/状态机操作由 F1/F2 服务按需扩展。
 */
@Slf4j
@Repository
public class BookRepository {

    private final JdbcTemplate jdbc;

    public BookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 注意：sqlite-jdbc 的 getObject(col, Class) 对 NULL 列会抛 "Bad value for type Integer"，
    // 必须用基础 getter + wasNull 手动判空（含 total_pages/chunk_count/识别率/embedding_dim 等可空列）
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

    /** 书籍列表（默认不含软删；支持 status/keyword 过滤） */
    public List<Book> list(String status, String keyword, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM books WHERE soft_delete = 0");
        List<Object> args = new java.util.ArrayList<>();
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

    public long count(String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM books WHERE soft_delete = 0");
        List<Object> args = new java.util.ArrayList<>();
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

    public Book findById(String bookId) {
        List<Book> list = jdbc.query("SELECT * FROM books WHERE book_id = ?", MAPPER, bookId);
        if (list.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return list.get(0);
    }

    public void insert(Book b) {
        jdbc.update("""
                INSERT INTO books (book_id, title, author, file_path, file_size, total_pages,
                                   status, build_sub_stage, build_progress,
                                   error_code, error_message, chunk_count, chapter_recognized_rate,
                                   embedding_model, embedding_dim, created_at, updated_at,
                                   last_ready_at, soft_delete, deleted_at, purge_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                b.getBookId(), b.getTitle(), b.getAuthor(), b.getFilePath(), b.getFileSize(), b.getTotalPages(),
                b.getStatus(), b.getBuildSubStage(), b.getBuildProgress(),
                b.getErrorCode(), b.getErrorMessage(), b.getChunkCount(), b.getChapterRecognizedRate(),
                b.getEmbeddingModel(), b.getEmbeddingDim(), b.getCreatedAt(), b.getUpdatedAt(),
                b.getLastReadyAt(), b.getSoftDelete(), b.getDeletedAt(), b.getPurgeAt());
    }
}
