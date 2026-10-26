package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG 评估数据访问层
 * 表：eval_datasets（题目） + eval_results（结果报告）
 */
@Slf4j
@Repository
public class EvalRepository {

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 数据库实体：单条评测题 */
    public static class EvalItem {
        public String itemId;
        public String category;
        public String bookId;
        public String question;
        public String groundTruthAnswer;
        public String expectedChunkIdsJson;   // 期望召回的 chunk_id 列表 JSON
        public String expectedCitationPagesJson;
        public String difficulty;
        public String createdAt;
    }

    /** 数据库实体：评测结果报告 */
    public static class EvalResult {
        public String evalRunId;
        public String startedAt;
        public String finishedAt;
        public String datasetVersion;
        public int totalItems;
        public int passItems;
        public double recallAt5;
        public double recallAt10;
        public double answerAccuracy;
        public double refusalAccuracy;
        public double citationAccuracy;
        public int avgFirstTokenMs;
        public int avgFullAnswerMs;
        public String failItemsJson;
        public String reportPath;
    }

    /** 按类别查询题目（sampleSize <= 0 表示全量） */
    public List<EvalItem> findItemsByCategories(List<String> categories, int sampleSize) {
        String baseSql = """
                SELECT item_id, category, book_id, question, ground_truth_answer,
                       expected_chunk_ids_json, expected_citation_pages_json,
                       difficulty, created_at
                  FROM eval_datasets
                 WHERE 1=1
                """;
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> args = new java.util.ArrayList<>();

        if (categories != null && !categories.isEmpty()) {
            sql.append(" AND category IN (");
            for (int i = 0; i < categories.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
                args.add(categories.get(i));
            }
            sql.append(")");
        }

        sql.append(" ORDER BY category, item_id");
        if (sampleSize > 0) {
            sql.append(" LIMIT ?");
            args.add(sampleSize);
        }

        return jdbc.query(sql.toString(), (rs, i) -> {
            EvalItem item = new EvalItem();
            item.itemId = rs.getString("item_id");
            item.category = rs.getString("category");
            item.bookId = rs.getString("book_id");
            item.question = rs.getString("question");
            item.groundTruthAnswer = rs.getString("ground_truth_answer");
            item.expectedChunkIdsJson = rs.getString("expected_chunk_ids_json");
            item.expectedCitationPagesJson = rs.getString("expected_citation_pages_json");
            item.difficulty = rs.getString("difficulty");
            item.createdAt = rs.getString("created_at");
            return item;
        }, args.toArray());
    }

    /** 统计指定类别总题数 */
    public int countItems(List<String> categories) {
        String baseSql = "SELECT COUNT(*) FROM eval_datasets WHERE 1=1";
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> args = new java.util.ArrayList<>();

        if (categories != null && !categories.isEmpty()) {
            sql.append(" AND category IN (");
            for (int i = 0; i < categories.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
                args.add(categories.get(i));
            }
            sql.append(")");
        }

        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 插入一条 RUNNING 状态的评估记录 */
    public void insertRunning(EvalResult r) {
        jdbc.update("""
                INSERT INTO eval_results
                  (eval_run_id, started_at, finished_at, dataset_version, total_items,
                   pass_items, recall_at_5, recall_at_10, answer_accuracy,
                   refusal_accuracy, citation_accuracy, avg_first_token_ms,
                   avg_full_answer_ms, fail_items_json, report_path)
                VALUES (?, ?, ?, ?, ?, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, NULL, NULL)
                """,
                r.evalRunId, r.startedAt, r.finishedAt, r.datasetVersion, r.totalItems);
    }

    /** 更新评估完成后的全部指标 */
    public void updateResult(EvalResult r) {
        jdbc.update("""
                UPDATE eval_results
                   SET finished_at = ?, pass_items = ?,
                       recall_at_5 = ?, recall_at_10 = ?,
                       answer_accuracy = ?, refusal_accuracy = ?, citation_accuracy = ?,
                       avg_first_token_ms = ?, avg_full_answer_ms = ?,
                       fail_items_json = ?, report_path = ?
                 WHERE eval_run_id = ?
                """,
                r.finishedAt, r.passItems,
                r.recallAt5, r.recallAt10,
                r.answerAccuracy, r.refusalAccuracy, r.citationAccuracy,
                r.avgFirstTokenMs, r.avgFullAnswerMs,
                r.failItemsJson, r.reportPath,
                r.evalRunId);
    }

    /** 分页查询评估结果列表（按 started_at DESC） */
    public List<EvalResult> listByPage(int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        return jdbc.query("""
                SELECT eval_run_id, started_at, finished_at, dataset_version,
                       total_items, pass_items, recall_at_5, recall_at_10,
                       answer_accuracy, refusal_accuracy, citation_accuracy,
                       avg_first_token_ms, avg_full_answer_ms,
                       fail_items_json, report_path
                  FROM eval_results
                 ORDER BY started_at DESC
                 LIMIT ? OFFSET ?
                """, (rs, i) -> mapResult(rs), pageSize, offset);
    }

    /** 按 ID 查单条评估报告 */
    public EvalResult findById(String evalRunId) {
        List<EvalResult> list = jdbc.query("""
                SELECT eval_run_id, started_at, finished_at, dataset_version,
                       total_items, pass_items, recall_at_5, recall_at_10,
                       answer_accuracy, refusal_accuracy, citation_accuracy,
                       avg_first_token_ms, avg_full_answer_ms,
                       fail_items_json, report_path
                  FROM eval_results
                 WHERE eval_run_id = ?
                """, (rs, i) -> mapResult(rs), evalRunId);
        return list.isEmpty() ? null : list.get(0);
    }

    private EvalResult mapResult(java.sql.ResultSet rs) throws java.sql.SQLException {
        EvalResult r = new EvalResult();
        r.evalRunId = rs.getString("eval_run_id");
        r.startedAt = rs.getString("started_at");
        r.finishedAt = rs.getString("finished_at");
        r.datasetVersion = rs.getString("dataset_version");
        r.totalItems = rs.getInt("total_items");
        r.passItems = rs.getInt("pass_items");
        r.recallAt5 = rs.getDouble("recall_at_5");
        r.recallAt10 = rs.getDouble("recall_at_10");
        r.answerAccuracy = rs.getDouble("answer_accuracy");
        r.refusalAccuracy = rs.getDouble("refusal_accuracy");
        r.citationAccuracy = rs.getDouble("citation_accuracy");
        r.avgFirstTokenMs = rs.getInt("avg_first_token_ms");
        r.avgFullAnswerMs = rs.getInt("avg_full_answer_ms");
        r.failItemsJson = rs.getString("fail_items_json");
        r.reportPath = rs.getString("report_path");
        return r;
    }
}
