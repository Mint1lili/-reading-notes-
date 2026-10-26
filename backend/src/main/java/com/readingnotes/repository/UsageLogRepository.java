package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * API 用量统计数据访问层
 * 表：usage_logs（见 Migration 0001 §16）
 */
@Slf4j
@Repository
public class UsageLogRepository {

    private final JdbcTemplate jdbc;

    public UsageLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按日期范围汇总 token 和金额 */
    public UsageSummary sumByDateRange(String startDate, String endDate) {
        String sql = """
                SELECT COALESCE(SUM(input_tokens), 0)   AS total_input,
                       COALESCE(SUM(output_tokens), 0)  AS total_output,
                       COALESCE(COUNT(*), 0)            AS total_calls,
                       COALESCE(SUM(cost_cny), 0)       AS total_cost
                  FROM usage_logs
                 WHERE log_date BETWEEN ? AND ?
                   AND status = 'SUCCESS'
                """;
        return jdbc.queryForObject(sql, (rs, i) -> {
            UsageSummary s = new UsageSummary();
            s.totalInputTokens = rs.getLong("total_input");
            s.totalOutputTokens = rs.getLong("total_output");
            s.totalCalls = rs.getLong("total_calls");
            s.totalCostCny = rs.getDouble("total_cost");
            return s;
        }, startDate, endDate);
    }

    /** 按 feature 分组汇总 */
    public List<FeatureUsage> sumByFeature(String startDate, String endDate) {
        String sql = """
                SELECT feature,
                       COALESCE(SUM(input_tokens), 0)  AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COUNT(*)                        AS calls
                  FROM usage_logs
                 WHERE log_date BETWEEN ? AND ?
                   AND status = 'SUCCESS'
                 GROUP BY feature
                 ORDER BY calls DESC
                """;
        return jdbc.query(sql, (rs, i) -> {
            FeatureUsage f = new FeatureUsage();
            f.feature = rs.getString("feature");
            f.inputTokens = rs.getLong("input_tokens");
            f.outputTokens = rs.getLong("output_tokens");
            f.calls = rs.getLong("calls");
            return f;
        }, startDate, endDate);
    }

    /** 按天分组时间线 */
    public List<DailyUsage> timelineByDay(String startDate, String endDate) {
        String sql = """
                SELECT log_date AS date,
                       COALESCE(SUM(input_tokens), 0)  AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COUNT(*)                        AS calls
                  FROM usage_logs
                 WHERE log_date BETWEEN ? AND ?
                   AND status = 'SUCCESS'
                 GROUP BY log_date
                 ORDER BY log_date
                """;
        return jdbc.query(sql, (rs, i) -> {
            DailyUsage d = new DailyUsage();
            d.date = rs.getString("date");
            d.inputTokens = rs.getLong("input_tokens");
            d.outputTokens = rs.getLong("output_tokens");
            d.calls = rs.getLong("calls");
            return d;
        }, startDate, endDate);
    }

    /** 查询单日用量（用于预算对比） */
    public DailyUsage sumForDay(String date) {
        String sql = """
                SELECT log_date AS date,
                       COALESCE(SUM(input_tokens), 0)  AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COUNT(*)                        AS calls
                  FROM usage_logs
                 WHERE log_date = ?
                   AND status = 'SUCCESS'
                """;
        List<DailyUsage> list = jdbc.query(sql, (rs, i) -> {
            DailyUsage d = new DailyUsage();
            d.date = rs.getString("date");
            d.inputTokens = rs.getLong("input_tokens");
            d.outputTokens = rs.getLong("output_tokens");
            d.calls = rs.getLong("calls");
            return d;
        }, date);
        return list.isEmpty() ? new DailyUsage() : list.get(0);
    }

    // ---- 返回类型 ----
    public static class UsageSummary {
        public long totalInputTokens;
        public long totalOutputTokens;
        public long totalCalls;
        public double totalCostCny;
    }

    public static class FeatureUsage {
        public String feature;
        public long calls;
        public long inputTokens;
        public long outputTokens;
    }

    public static class DailyUsage {
        public String date;
        public long inputTokens;
        public long outputTokens;
        public long calls;
    }
}
