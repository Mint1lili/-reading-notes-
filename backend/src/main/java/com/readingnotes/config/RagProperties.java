package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 默认参数（application.yml 的 rag.*，对应需求 F5.2）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int recallTopK;
    private int rerankTopN;
    private double similarityThreshold;
    private int chunkSize;
    private int overlap;
    private int multiTurnN;
    private boolean enableRerank;
    private boolean enableMetadataFilter;
}
