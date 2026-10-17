package com.readingnotes.repository;

import com.readingnotes.entity.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 切片数据访问（chunks 表）
 * 原书切片由 F2 构建任务写入；回流笔记切片由 F4 写入，两者共用本表。
 */
@Slf4j
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Integer getInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<Chunk> MAPPER = (rs, i) -> {
        Chunk c = new Chunk();
        c.setChunkId(rs.getString("chunk_id"));
        c.setBookId(rs.getString("book_id"));
        c.setNoteId(rs.getString("note_id"));
        c.setChunkType(rs.getString("chunk_type"));
        c.setContent(rs.getString("content"));
        c.setPage(getInt(rs, "page"));
        c.setChapterPath(rs.getString("chapter_path"));
        c.setChapterTitle(rs.getString("chapter_title"));
        c.setPosition(rs.getInt("position"));
        c.setWordCount(getInt(rs, "word_count"));
        c.setEmbeddingStatus(rs.getString("embedding_status"));
        c.setEmbeddingBatchId(getInt(rs, "embedding_batch_id"));
        c.setVectorId(rs.getString("vector_id"));
        c.setCreatedAt(rs.getString("created_at"));
        return c;
    };

    /**
     * 批量写入切片（切片阶段一次性落库，embedding_status=PENDING）。
     * 断点续传依赖这里的 chunk_id 稳定：重试时先 deleteByBook 再重写，批次号保持一致。
     */
    public void batchInsertBookChunks(String bookId, List<Chunk> chunks, String createdAt) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Object[]> args = chunks.stream().map(c -> new Object[]{
                c.getChunkId(), bookId, c.getChunkType() == null ? "book" : c.getChunkType(),
                c.getContent(), c.getPage(), c.getChapterPath(), c.getChapterTitle(),
                c.getPosition(), c.getWordCount(),
                c.getEmbeddingStatus() == null ? "PENDING" : c.getEmbeddingStatus(),
                c.getEmbeddingBatchId(), c.getVectorId(), createdAt
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO chunks (chunk_id, book_id, chunk_type, content, page, chapter_path,
                                    chapter_title, position, word_count, embedding_status,
                                    embedding_batch_id, vector_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
    }

    /** 标记某批切片已向量化（vector_id = chunk_id，Chroma 侧 id 与之对齐） */
    public void markBatchDone(String bookId, int batchId) {
        jdbc.update("""
                UPDATE chunks SET embedding_status = 'DONE', vector_id = chunk_id
                WHERE book_id = ? AND embedding_batch_id = ?
                """, bookId, batchId);
    }

    /** 标记某批切片向量化失败 */
    public void markBatchFailed(String bookId, int batchId) {
        jdbc.update("""
                UPDATE chunks SET embedding_status = 'FAILED'
                WHERE book_id = ? AND embedding_batch_id = ?
                """, bookId, batchId);
    }

    /** 取某本书的切片（按 position 升序，用于批次续跑） */
    public List<Chunk> listByBook(String bookId) {
        return jdbc.query(
                "SELECT * FROM chunks WHERE book_id = ? AND chunk_type = 'book' ORDER BY position ASC",
                MAPPER, bookId);
    }

    /** 切片预览分页（可按章节路径前缀过滤） */
    public List<Chunk> pageByBook(String bookId, String chapterPathPrefix, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM chunks WHERE book_id = ? AND chunk_type = 'book'");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(bookId);
        if (chapterPathPrefix != null && !chapterPathPrefix.isBlank()) {
            sql.append(" AND chapter_path LIKE ?");
            args.add(chapterPathPrefix + "%");
        }
        sql.append(" ORDER BY position ASC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long countByBook(String bookId, String chapterPathPrefix) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM chunks WHERE book_id = ? AND chunk_type = 'book'");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(bookId);
        if (chapterPathPrefix != null && !chapterPathPrefix.isBlank()) {
            sql.append(" AND chapter_path LIKE ?");
            args.add(chapterPathPrefix + "%");
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 字数分布（预览页用）：min/max/avg 由 SQL 聚合，避免把全部切片读进内存 */
    public int[] wordCountStats(String bookId) {
        return jdbc.query("""
                SELECT COALESCE(MIN(word_count),0) mn, COALESCE(MAX(word_count),0) mx,
                       COALESCE(AVG(word_count),0) av
                FROM chunks WHERE book_id = ? AND chunk_type = 'book'
                """, rs -> {
            if (rs.next()) {
                return new int[]{rs.getInt("mn"), rs.getInt("mx"), (int) Math.round(rs.getDouble("av"))};
            }
            return new int[]{0, 0, 0};
        }, bookId);
    }

    /** 已完成的切片数（构建状态接口的 chunk_count_actual） */
    public int countDone(String bookId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE book_id = ? AND chunk_type = 'book' AND embedding_status = 'DONE'",
                Integer.class, bookId);
        return n == null ? 0 : n;
    }

    public void deleteByBook(String bookId) {
        jdbc.update("DELETE FROM chunks WHERE book_id = ? AND chunk_type = 'book'", bookId);
    }
}
