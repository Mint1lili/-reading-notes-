package com.readingnotes.repository;

import com.readingnotes.entity.BuildProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 构建进度数据访问（build_progress 表）—— 断点续传的核心
 */
@Slf4j
@Repository
public class BuildProgressRepository {

    private final JdbcTemplate jdbc;

    public BuildProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Integer getInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<BuildProgress> MAPPER = (rs, i) -> {
        BuildProgress p = new BuildProgress();
        p.setBookId(rs.getString("book_id"));
        p.setLastCompletedBatch(rs.getInt("last_completed_batch"));
        p.setTotalBatches(getInt(rs, "total_batches"));
        p.setChunkCountPlanned(getInt(rs, "chunk_count_planned"));
        p.setChunkCountActual(getInt(rs, "chunk_count_actual"));
        p.setFailedBatchesJson(rs.getString("failed_batches_json"));
        p.setChapterRecognizedRate(getDouble(rs, "chapter_recognized_rate"));
        p.setBuildStartedAt(rs.getString("build_started_at"));
        p.setLastUpdatedAt(rs.getString("last_updated_at"));
        return p;
    };

    public BuildProgress findOrNull(String bookId) {
        List<BuildProgress> list = jdbc.query(
                "SELECT * FROM build_progress WHERE book_id = ?", MAPPER, bookId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 开始一次构建：重置断点（首次构建 / 重建时调用）。
     * 保留 build_started_at 用于统计"构建耗时 ≤ 5 分钟"（AC1）。
     */
    public void start(String bookId, String now) {
        jdbc.update("""
                INSERT INTO build_progress (book_id, last_completed_batch, total_batches,
                                            chunk_count_planned, chunk_count_actual,
                                            failed_batches_json, chapter_recognized_rate,
                                            build_started_at, last_updated_at)
                VALUES (?, 0, NULL, NULL, 0, NULL, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE
                    last_completed_batch = 0, total_batches = NULL, chunk_count_planned = NULL,
                    chunk_count_actual = 0, failed_batches_json = NULL,
                    chapter_recognized_rate = NULL, build_started_at = VALUES(build_started_at),
                    last_updated_at = VALUES(last_updated_at)
                """, bookId, now, now);
    }

    /** 切片完成后写入批次规划 */
    public void savePlan(String bookId, int totalBatches, int planned, double chapterRate, String now) {
        jdbc.update("""
                UPDATE build_progress SET total_batches = ?, chunk_count_planned = ?,
                                          chapter_recognized_rate = ?, last_updated_at = ?
                WHERE book_id = ?
                """, totalBatches, planned, chapterRate, now, bookId);
    }

    /** 每完成一批推进断点 */
    public void markBatchCompleted(String bookId, int batchId, int actualDone, String now) {
        jdbc.update("""
                UPDATE build_progress SET last_completed_batch = GREATEST(last_completed_batch, ?),
                                          chunk_count_actual = ?, last_updated_at = ?
                WHERE book_id = ?
                """, batchId + 1, actualDone, now, bookId);
    }

    /** 记录失败批次（JSON 数组，如 "[3,7]"） */
    public void saveFailedBatches(String bookId, String json, String now) {
        jdbc.update("UPDATE build_progress SET failed_batches_json = ?, last_updated_at = ? WHERE book_id = ?",
                json, now, bookId);
    }

    /**
     * 显式回退断点（单批次重试 / from_batch 指定）。
     * 与 {@link #markBatchCompleted} 的 GREATEST 语义相反，这里是"设置"而非"推进"。
     */
    public void resetResumePoint(String bookId, int fromBatch, String now) {
        jdbc.update("""
                UPDATE build_progress SET last_completed_batch = ?, failed_batches_json = NULL,
                                          last_updated_at = ?
                WHERE book_id = ?
                """, Math.max(0, fromBatch), now, bookId);
    }

    public void deleteByBook(String bookId) {
        jdbc.update("DELETE FROM build_progress WHERE book_id = ?", bookId);
    }
}
