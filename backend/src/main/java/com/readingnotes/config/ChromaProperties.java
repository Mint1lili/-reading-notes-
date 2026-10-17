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

    /**
     * API 版本，取值 {@code v2} 或 {@code v1}。
     * <ul>
     *   <li>{@code v2}：chromadb &gt;= 1.0。路径为
     *       {@code /api/v2/tenants/{tenant}/databases/{database}/collections}，集合按 UUID 寻址。</li>
     *   <li>{@code v1}：chromadb 0.4.x ~ 0.6.x。路径为 {@code /api/v1/collections}，集合按名称寻址。</li>
     * </ul>
     * 默认取 {@code v2}：chromadb 1.x 已移除 v1 的服务端实现，访问 {@code /api/v1/*} 会返回
     * {@code 410 {"error":"Unimplemented","message":"The v1 API is deprecated. Please use /v2 apis"}}。
     */
    private String apiVersion = "v2";

    /** v2 路径中的 tenant 段，单节点默认库为 default_tenant */
    private String tenant = "default_tenant";

    /** v2 路径中的 database 段，单节点默认库为 default_database */
    private String database = "default_database";
}
