package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 远端 API 向量化实现（ai.embedding.mode=api，默认）
 * 独立对接智谱开放平台 Embedding-3（OpenAI 兼容 /embeddings），
 * 与聊天大模型（联通云 Qwen）解耦，互不影响。
 *
 * 智谱端点：https://open.bigmodel.cn/api/paas/v4/embeddings
 * 鉴权：Authorization: Bearer {api-key}
 * 免费：新用户赠送 2000 万 tokens（比赛测试绰绰有余）
 *
 * 维度：Embedding-3 默认 2048 维（与豆包 1024 不同）。
 * 若 Chroma 已有旧维度数据需删集合重建（ErrorCode.DIM_MISMATCH 兜底）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.embedding.mode", havingValue = "api", matchIfMissing = true)
public class ApiEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dim;

    public ApiEmbeddingProvider(
            @Value("${ai.embedding.api-base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${ai.embedding.api-key:}") String apiKey,
            @Value("${ai.embedding.api-model:embedding-3}") String model,
            @Value("${ai.embedding.api-dim:2048}") int dim) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dim = dim;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        ensureKey();
        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", model)
                    .set("input", mapper.valueToTree(texts)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("zhipu embeddings status={} body={}", resp.statusCode(), truncate(resp.body()));
                throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
            }
            List<List<Float>> result = new ArrayList<>();
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode item : root.path("data")) {
                List<Float> vector = new ArrayList<>();
                for (JsonNode v : item.path("embedding")) {
                    vector.add((float) v.asDouble());
                }
                result.add(vector);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("zhipu embeddings failed", e);
            throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
        }
    }

    @Override
    public int dimension() {
        return dim;
    }

    private void ensureKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(70003, 503,
                    "未配置向量化 API Key，请在环境变量中设置 AI_EMBEDDING_API_KEY 后重启服务");
        }
    }

    private String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }
}
