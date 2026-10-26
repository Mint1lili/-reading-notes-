package com.readingnotes.controller;

import com.readingnotes.ai.FeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 反馈与版本激活接口（F3.6，API 文档 §7.10/7.11）
 *
 *  - POST /api/v1/ai/{target_type}/{target_id}/feedback
 *      body={vote(1|-1), reason?, comment?} → 201 {feedback_id}
 *      target_type = ai_version（回填会话消息 feedback_id）| note
 *  - POST /api/v1/ai/versions/{version_id}/activate
 *      → 激活该版本，同目标同类型其余版本置 0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiFeedbackController {

    private final FeedbackService feedbackService;

    public AiFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** 提交反馈（§7.10） */
    @PostMapping("/{target_type}/{target_id}/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@PathVariable("target_type") String targetType,
                                                        @PathVariable("target_id") String targetId,
                                                        @RequestBody Map<String, Object> body) {
        Integer vote = body.get("vote") == null ? null : ((Number) body.get("vote")).intValue();
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        String feedbackId = feedbackService.submitFeedback(targetType, targetId, vote, reason, comment);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("feedback_id", feedbackId));
    }

    /** 版本激活切换（§7.11） */
    @PostMapping("/versions/{version_id}/activate")
    public Map<String, Object> activate(@PathVariable("version_id") String versionId) {
        return feedbackService.activateVersion(versionId);
    }
}
