package com.readingnotes.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 书本内问答请求（API 文档 §7.4）
 * 字段同时兼容两种风格：
 * - 文档/前端：下划线（book_id, top_k...）
 * - 内部/测试：驼峰（bookId, topK...）
 */
@Data
public class QaStreamRequest {

    /** 书籍 ID（必填） */
    @NotBlank(message = "book_id 不能为空")
    @JsonProperty("book_id")
    @JsonAlias("bookId")
    private String bookId;

    /** 多轮会话 ID；不存在则后端创建 */
    @JsonProperty("session_id")
    @JsonAlias("sessionId")
    private String sessionId;

    /** 用户问题（必填） */
    @NotBlank(message = "question 不能为空")
    private String question;

    /** 以下覆盖全局 RAG 配置（F5.2），不传则用默认 */
    @JsonProperty("enable_metadata_filter")
    @JsonAlias("enableMetadataFilter")
    private Boolean enableMetadataFilter;

    @JsonProperty("enable_rerank")
    @JsonAlias("enableRerank")
    private Boolean enableRerank;

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
