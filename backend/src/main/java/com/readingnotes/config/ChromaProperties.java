package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chroma 向量库配置（application.yml 的 chroma.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "chroma")
public class ChromaProperties {

    private String baseUrl;
    private long healthCheckIntervalMs;
}
