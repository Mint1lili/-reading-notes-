package com.readingnotes.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * F3.4 基于笔记问答 SSE 请求（API 文档 §7.6）
 * 字段同时兼容下划线（book_id / session_id）与驼峰（bookId / sessionId）两种风格。
 * scope 三选一：
 *  - book_only       仅原书
 *  - notes_only      仅笔记（该书的笔记，notes_all 中按 note_id 过滤）
 *  - book_and_notes  原书 + 笔记（默认）
 */
@Data
public class NoteQaStreamRequest {

    /** 目标书籍（必填） */
    @JsonProperty("book_id")
    @JsonAlias("bookId")
    @NotBlank(message = "book_id 不能为空")
    private String bookId;

    /** 检索范围：book_only / notes_only / book_and_notes（默认 book_and_notes） */
    private String scope;

    /** 多轮上下文归属；不存在则后端创建 */
    @JsonProperty("session_id")
    @JsonAlias("sessionId")
    private String sessionId;

    @NotBlank(message = "question 不能为空")
    private String question;

    /** 以下为可选覆盖，默认取全局配置 */
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

    private Double temperature;

    /** 规范化 scope，非法值回落 book_and_notes */
    public String normalizedScope() {
        if (scope == null) {
            return "book_and_notes";
        }
        String s = scope.trim().toLowerCase();
        if (s.equals("book_only") || s.equals("notes_only") || s.equals("book_and_notes")) {
            return s;
        }
        return "book_and_notes";
    }
}
