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
 * 本地向量化实现（ai.embedding.mode=local）
 * 对接本地部署的开源向量模型 HTTP 服务（如 bge-small-zh）。
 *
 * 本地服务约定（OpenAI 兼容 /embeddings）：
 *   POST http://localhost:8001/v1/embeddings
 *   {"model":"bge-small-zh","input":["文本1","文本2"]}
 *   → {"data":[{"embedding":[...]}, ...]}
 *
 * 维度由服务返回的实际向量长度决定（bge-small-zh 为 512，与 API 的 1024 不同）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.embedding.mode", havingValue = "local")
public class LocalEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;

    public LocalEmbeddingProvider(
            @Value("${ai.embedding.local-base-url:http://localhost:8001/v1}") String baseUrl,
            @Value("${ai.embedding.local-model:bge-small-zh}") String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", model)
                    .set("input", mapper.valueToTree(texts)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("local embedding http status={} body={}", resp.statusCode(), truncate(resp.body()));
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
            log.error("local embedding failed", e);
            throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
        }
    }

    @Override
    public int dimension() {
        // 本地模型维度：由服务返回向量长度决定；此处给出默认值，
        // 实际以首次 embed 结果为准（Chroma 集合创建时以实际维度校验）。
        return 512;
    }

    private String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }
}
