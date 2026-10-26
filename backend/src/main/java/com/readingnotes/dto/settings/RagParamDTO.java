package com.readingnotes.dto.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * RAG 参数请求/响应 DTO（接口 10.2）
 * PUT 支持单字段更新
 */
@Data
public class RagParamDTO {

    @JsonProperty("recall_top_k")
    private Integer recallTopK;

    @JsonProperty("rerank_top_n")
    private Integer rerankTopN;

    @JsonProperty("similarity_threshold")
    private Double similarityThreshold;

    @JsonProperty("chunk_size")
    private Integer chunkSize;

    private Integer overlap;

    @JsonProperty("multi_turn_n")
    private Integer multiTurnN;

    @JsonProperty("enable_rerank")
    private Boolean enableRerank;

    @JsonProperty("enable_metadata_filter")
    private Boolean enableMetadataFilter;
}
