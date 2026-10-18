package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AI 产物数据访问（API 文档 §9.9「AI 产物一键存为笔记」）
 *
 * <p>数据来源两张表（数据库设计 §5.10 / §5.13）：
 * <ul>
 *   <li>{@code ai_versions}：摘要 / 导图 / 单条问答的正式产物版本（含 content）</li>
 *   <li>{@code chat_messages}：多轮会话消息（问答对按最后两条消息取）</li>
 * </ul>
 * 表由 B 的 F3 模块写入，此 Repository 只读。
 */
@Slf4j
@Repository
public class AiArtifactRepository {

    private final JdbcTemplate jdbc;

    public AiArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** AI 产物版本（ai_versions 一行） */
    public record AiVersion(String versionId, String targetType, String targetId,
                            String artifactType, String content, String createdAt) {
    }

    /** 多轮会话的一轮问答对 */
    public record QaPair(String question, String answer) {
    }

    /** 按 version_id 查产物版本；不存在返回 null */
    public AiVersion findVersion(String versionId) {
        List<AiVersion> list = jdbc.query("""
                        SELECT version_id, target_type, target_id, artifact_type, content, created_at
                          FROM ai_versions WHERE version_id = ?
                        """,
                (rs, i) -> new AiVersion(
                        rs.getString("version_id"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("artifact_type"),
                        rs.getString("content"),
                        rs.getString("created_at")),
                versionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 取会话最后一轮问答：按时间倒序取 2 条，
     * 末条为 assistant 则作为答案、其上一条 user 作为问题。
     * 取不到返回 null。
     */
    public QaPair findLatestQa(String sessionId) {
        List<String[]> rows = jdbc.query("""
                        SELECT role, content FROM chat_messages
                         WHERE session_id = ?
                         ORDER BY created_at DESC, message_id DESC
                         LIMIT 2
                        """,
                (rs, i) -> new String[]{rs.getString("role"), rs.getString("content")},
                sessionId);
        if (rows.isEmpty()) {
            return null;
        }
        String answer = null;
        String question = null;
        if ("assistant".equals(rows.get(0)[0])) {
            answer = rows.get(0)[1];
            if (rows.size() > 1 && "user".equals(rows.get(1)[0])) {
                question = rows.get(1)[1];
            }
        } else if ("user".equals(rows.get(0)[0])) {
            // 只有提问还没有回答（AI 正在流式输出）
            question = rows.get(0)[1];
        }
        if (answer == null && question == null) {
            return null;
        }
        return new QaPair(question, answer);
    }

    /** 会话所属书籍（跨书会话 scope='cross' 时为 null） */
    public String findSessionBookId(String sessionId) {
        List<String> list = jdbc.query(
                "SELECT book_id FROM chat_sessions WHERE session_id = ?",
                (rs, i) -> rs.getString(1), sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 书籍标题（用于自动标题兜底） */
    public String findBookTitle(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return null;
        }
        List<String> list = jdbc.query(
                "SELECT title FROM books WHERE book_id = ?",
                (rs, i) -> rs.getString(1), bookId);
        return list.isEmpty() ? null : list.get(0);
    }
}
