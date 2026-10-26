package com.readingnotes.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 反馈与版本管理（F3.6，API 文档 §7.10/7.11）
 *
 *  - POST /ai/{target_type}/{target_id}/feedback：提交反馈（vote=1 赞 / -1 踩 + 可选 reason/comment）
 *    target_type = ai_version | note；ai_version 反馈时同步回填对应会话消息的 feedback_id
 *  - POST /ai/versions/{version_id}/activate：版本激活切换（同目标同类型仅 1 条 is_active）
 *
 * 落库表：note_feedback（feedback_id/target_type/target_id/vote/reason/comment/created_at）。
 */
@Slf4j
@Service
public class FeedbackService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FeedbackService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 提交反馈。ai_version 反馈会沿 version→task→session→assistant 消息回填 feedback_id。
     *
     * @return feedback_id
     */
    public String submitFeedback(String targetType, String targetId, Integer vote,
                                 String reason, String comment) {
        String type = normalizeTargetType(targetType);
        String id = targetId == null ? "" : targetId.trim();
        if (id.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "target_id 不能为空");
        }
        if (vote == null || (vote != 1 && vote != -1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vote 仅支持 1（赞）或 -1（踩）");
        }
        ensureTargetExists(type, id);

        String feedbackId = "fb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("""
                INSERT INTO note_feedback (feedback_id, target_type, target_id, vote, reason, comment, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, feedbackId, type, id, vote, reason, comment, Instant.now().toString());

        if ("ai_version".equals(type)) {
            backfillChatMessage(feedbackId, id);
        }
        log.info("feedback submitted fb={} type={} target={} vote={}", feedbackId, type, id, vote);
        return feedbackId;
    }

    /**
     * 版本激活切换：指定版本 is_active=1，同目标同类型其余版本 is_active=0。
     */
    public Map<String, Object> activateVersion(String versionId) {
        versionId = versionId == null ? "" : versionId.trim();
        if (versionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "version_id 不能为空");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT target_type, target_id, artifact_type, is_active FROM ai_versions WHERE version_id=?
                """, versionId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND);
        }
        Map<String, Object> v = rows.get(0);
        String targetType = String.valueOf(v.get("target_type"));
        String targetId = String.valueOf(v.get("target_id"));
        String artifactType = String.valueOf(v.get("artifact_type"));

        jdbc.update("UPDATE ai_versions SET is_active=0 "
                        + "WHERE target_type=? AND target_id=? AND artifact_type=?",
                targetType, targetId, artifactType);
        jdbc.update("UPDATE ai_versions SET is_active=1 WHERE version_id=?", versionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version_id", versionId);
        result.put("target_type", targetType);
        result.put("target_id", targetId);
        result.put("artifact_type", artifactType);
        result.put("is_active", true);
        return result;
    }

    // ==================== 内部 ====================

    private String normalizeTargetType(String targetType) {
        if (targetType == null) {
            return "";
        }
        String s = targetType.trim().toLowerCase();
        if (s.equals("ai_version") || s.equals("note")) {
            return s;
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "target_type 仅支持 ai_version 或 note");
    }

    private void ensureTargetExists(String type, String id) {
        if ("note".equals(type)) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT note_id FROM notes WHERE note_id=? AND soft_delete=0", id);
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
            }
        } else {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT version_id FROM ai_versions WHERE version_id=?", id);
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.VERSION_NOT_FOUND);
            }
        }
    }

    /** 沿 ai_version → task → session → 最近一条 assistant 消息回填 feedback_id（仅在能定位会话时） */
    private void backfillChatMessage(String feedbackId, String versionId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT t.target_id AS session_id FROM ai_tasks t
                    WHERE t.task_id = (SELECT task_id FROM ai_versions WHERE version_id = ?)
                      AND t.target_type = 'session'
                    """, versionId);
            if (rows.isEmpty()) {
                log.info("ai_version {} 无关联会话，跳过 chat_messages 回填", versionId);
                return;
            }
            String sessionId = String.valueOf(rows.get(0).get("session_id"));
            int n = jdbc.update("""
                    UPDATE chat_messages SET feedback_id=?
                    WHERE session_id=? AND role='assistant' ORDER BY created_at DESC LIMIT 1
                    """, feedbackId, sessionId);
            log.info("chat_messages backfilled fb={} session={} rows={}", feedbackId, sessionId, n);
        } catch (Exception e) {
            log.warn("backfill chat_messages failed version={}", versionId, e);
        }
    }
}
