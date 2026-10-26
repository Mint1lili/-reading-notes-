package com.readingnotes.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 跨书问答请求（API 文档 §7.5）
 * 字段同时兼容下划线（book_ids / include_notes）与驼峰（bookIds / includeNotes）两种风格。
 */
@Data
public class CrossQaStreamRequest {

    /** 参与检索的书；为空表示全部已完成（READY）书籍 */
    @JsonProperty("book_ids")
    @JsonAlias("bookIds")
    private List<String> bookIds;

    /** 是否纳入 notes_all（全局笔记集合，默认 true，对应 F3.3 独立开关） */
    @JsonProperty("include_notes")
    @JsonAlias("includeNotes")
    private Boolean includeNotes;

    /** 多轮会话 ID；不存在则后端创建 */
    @JsonProperty("session_id")
    @JsonAlias("sessionId")
    private String sessionId;

    /** 用户问题（必填） */
    @NotBlank(message = "question 不能为空")
    private String question;

    /** 以下覆盖全局 RAG 配置（F5.2），不传则用默认 */
    @JsonProperty("top_k")
    @JsonAlias("topK")
    private Integer topK;

    @JsonProperty("rerank_top_n")
    @JsonAlias("rerankTopN")
    private Integer rerankTopN;

    @JsonProperty("similarity_threshold")
    @JsonAlias("similarityThreshold")
    private Double similarityThreshold;

    @JsonProperty("multi_turn_n")
    @JsonAlias("multiTurnN")
    private Integer multiTurnN;

    /** 大模型采样温度，默认 0.3 */
    private Double temperature;
}
