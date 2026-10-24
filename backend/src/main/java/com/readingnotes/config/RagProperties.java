package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 默认参数（application.yml 的 rag.*，对应需求 F5.2）。
 * <p>
 * 这里的值是"出厂默认"，运行时以数据库中的 settings / prompt 版本为准，
 * 便于不改配置就调参；两者不一致时以数据库为准。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 向量召回条数（粗排上限），过大会拉长重排耗时 */
    private int recallTopK;

    /** 重排后送入大模型的条数（精排上限），直接决定 prompt 长度与回答聚焦度 */
    private int rerankTopN;

    /** 相似度阈值 0~1，低于该值的片段不参与回答，用于触发"本书中未提及" */
    private double similarityThreshold;

    /** 默认切片字数（中粒度），可在构建时按书覆盖 */
    private int chunkSize;

    /** 相邻切片重叠字数，保证跨片语义不被截断 */
    private int overlap;

    /** 多轮对话携带的历史轮数 */
    private int multiTurnN;

    /** 是否启用重排（关闭后仅按向量相似度排序，速度更快但精度下降） */
    private boolean enableRerank;

    /** 是否按章节/页码等元数据做前置过滤（F3 章节问答依赖此项） */
    private boolean enableMetadataFilter;
}
