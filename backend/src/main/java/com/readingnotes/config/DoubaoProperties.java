package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 豆包大模型 API 配置（application.yml 的 doubao.*）
 * key 只允许来自环境变量 DOUBAO_API_KEY，绝不写入代码或配置文件
 */
@Data
@Component
@ConfigurationProperties(prefix = "doubao")
public class DoubaoProperties {

    /** 环境变量注入，空则 AI 调用降级（页面提示配置缺失） */
    private String apiKey;
    private String baseUrl;
    private String chatModel;
    private String embeddingModel;
    private int embeddingDim;
    private int batchSize;
    private int maxRetries;

    /** Embedding 单次请求超时（秒），需求 §8 要求 30s 超时提示「向量化服务超时」 */
    private int embeddingTimeoutSeconds = 35;
}
