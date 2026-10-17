package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.DoubaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 豆包大模型 API 客户端（火山引擎 Ark · OpenAI 兼容端点）
 * - Chat：POST /chat/completions（支持 stream）
 * - Embedding：POST /embeddings，模型 Doubao-embedding-text-240715，1024 维
 * - 429 指数退避：1s→2s→4s，最多 maxRetries 次（需求 F2.4）
 * 安全：api-key 只来自环境变量，前端不可见（需求 §6.2）
 */
@Slf4j
@Component
public class DoubaoClient {

    private final DoubaoProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public DoubaoClient(DoubaoProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /** Chat 非流式调用，返回完整文本 */
    public String chat(List<ChatMessage> messages, double temperature, int maxTokens) {
        ensureKey();
        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", props.getChatModel())
                    .put("temperature", temperature)
                    .put("max_tokens", maxTokens)
                    .set("messages", mapper.valueToTree(messages)));
            JsonNode resp = post("/chat/completions", body);
            return resp.path("choices").path(0).path("message").path("content").asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("doubao chat failed trace={}", com.readingnotes.common.TraceContext.current(), e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    /**
     * Chat 流式调用（SSE）。每收到一个 content 增量回调一次 onDelta。
     * 供 F3.2/F3.3 书本问答流式输出使用。
     */
    public void chatStream(List<ChatMessage> messages, double temperature, int maxTokens,
                           java.util.function.Consumer<String> onDelta) {
        ensureKey();
        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", props.getChatModel())
                    .put("temperature", temperature)
                    .put("max_tokens", maxTokens)
                    .put("stream", true)
                    .set("messages", mapper.valueToTree(messages)));
            HttpRequest req = baseRequest("/chat/completions")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            }
            // 逐行解析 SSE: data: {...}
            for (String line : resp.body().split("\n")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode node = mapper.readTree(payload);
                String delta = node.path("choices").path(0).path("delta").path("content").asText(null);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("doubao chat stream failed trace={}", com.readingnotes.common.TraceContext.current(), e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    /** Embedding 批量调用，返回逐条向量；失败重试最多 maxRetries 次 */
    public List<List<Float>> embed(List<String> texts) {
        ensureKey();
        List<List<Float>> result = new ArrayList<>();
        int retries = 0;
        while (true) {
            try {
                String body = mapper.writeValueAsString(mapper.createObjectNode()
                        .put("model", props.getEmbeddingModel())
                        .set("input", mapper.valueToTree(texts)));
                JsonNode resp = post("/embeddings", body);
                for (JsonNode item : resp.path("data")) {
                    List<Float> vector = new ArrayList<>();
                    for (JsonNode v : item.path("embedding")) {
                        vector.add((float) v.asDouble());
                    }
                    result.add(vector);
                }
                return result;
            } catch (BusinessException e) {
                if (e.getCode() == 70003 && retries < props.getMaxRetries()) {
                    retries++;
                    long sleep = (long) Math.pow(2, retries) * 1000; // 1s→2s→4s
                    log.warn("embed retry {}/{} sleep={}ms trace={}", retries, props.getMaxRetries(), sleep,
                            com.readingnotes.common.TraceContext.current());
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (retries < props.getMaxRetries()) {
                    retries++;
                    long sleep = (long) Math.pow(2, retries) * 1000;
                    log.warn("embed retry {}/{} sleep={}ms trace={}", retries, props.getMaxRetries(), sleep,
                            com.readingnotes.common.TraceContext.current());
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
                    }
                    continue;
                }
                log.error("embed failed trace={}", com.readingnotes.common.TraceContext.current(), e);
                throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
            }
        }
    }

    /**
     * Embedding 带可等待标志：批量构建用。
     * <p>
     * 与 {@link #embed(List)} 的区别：429 限流时设置 {@code rateLimitedFlag[0]=true} 并继续
     * 指数退避重试，让构建任务能区分"限流但可续"与"彻底失败"，从而决定是否标记该批次失败。
     * 超时时间放宽到 embeddingTimeoutSeconds（大文档单批可能 >60s）。
     *
     * @param texts          待向量化文本（≤ batchSize）
     * @param rateLimited    出参：本次调用是否遇到 429
     */
    public List<List<Float>> embed(List<String> texts, boolean[] rateLimited) {
        ensureKey();
        int retries = 0;
        int maxRetries = props.getMaxRetries();
        int timeout = props.getEmbeddingTimeoutSeconds() > 0 ? props.getEmbeddingTimeoutSeconds() : 60;
        while (true) {
            try {
                String body = mapper.writeValueAsString(mapper.createObjectNode()
                        .put("model", props.getEmbeddingModel())
                        .set("input", mapper.valueToTree(texts)));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(props.getBaseUrl() + "/embeddings"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .timeout(Duration.ofSeconds(timeout))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 429) {
                    if (rateLimited != null) {
                        rateLimited[0] = true;
                    }
                    if (retries < maxRetries) {
                        retries++;
                        sleepBackoff(retries);
                        continue;
                    }
                    log.error("embed rate limited after {} retries trace={}", retries,
                            com.readingnotes.common.TraceContext.current());
                    throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
                }
                if (resp.statusCode() != 200) {
                    log.error("embed http status={} body={} trace={}", resp.statusCode(),
                            truncate(resp.body()), com.readingnotes.common.TraceContext.current());
                    throw new BusinessException(ErrorCode.DOUBAO_DOWN);
                }
                JsonNode root = mapper.readTree(resp.body());
                List<List<Float>> out = new ArrayList<>();
                for (JsonNode item : root.path("data")) {
                    List<Float> vector = new ArrayList<>();
                    for (JsonNode v : item.path("embedding")) {
                        vector.add((float) v.asDouble());
                    }
                    out.add(vector);
                }
                if (out.size() != texts.size()) {
                    log.error("embed size mismatch input={} output={}", texts.size(), out.size());
                    throw new BusinessException(ErrorCode.DOUBAO_DOWN);
                }
                return out;
            } catch (BusinessException e) {
                // 网络类失败（非 429）也退避重试，超时/不可用都可能瞬时
                if (e.getCode() == 70003 && retries < maxRetries) {
                    retries++;
                    sleepBackoff(retries);
                    continue;
                }
                throw e;
            } catch (java.net.http.HttpTimeoutException te) {
                log.error("embed timeout {}s trace={}", timeout,
                        com.readingnotes.common.TraceContext.current());
                throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
            } catch (Exception e) {
                if (retries < maxRetries) {
                    retries++;
                    sleepBackoff(retries);
                    continue;
                }
                log.error("embed failed trace={}", com.readingnotes.common.TraceContext.current(), e);
                throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
            }
        }
    }

    /** 指数退避 1s→2s→4s（需求 F2.4） */
    private void sleepBackoff(int attempt) {
        long sleep = (long) Math.pow(2, attempt) * 1000L;
        log.warn("embed retry {}/{} sleep={}ms trace={}", attempt, props.getMaxRetries(), sleep,
                com.readingnotes.common.TraceContext.current());
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
        }
    }

    /** 检查 key 是否配置；缺失时给出明确中文提示而非崩溃 */
    private void ensureKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new BusinessException(70003, 503,
                    "未配置豆包 API Key，请在环境变量中设置 DOUBAO_API_KEY 后重启服务");
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .timeout(Duration.ofSeconds(60));
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpRequest req = baseRequest(path)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.error("doubao http {} status={} body={} trace={}", path, resp.statusCode(),
                    truncate(resp.body()), com.readingnotes.common.TraceContext.current());
            throw new BusinessException(ErrorCode.DOUBAO_DOWN);
        }
        return mapper.readTree(resp.body());
    }

    private String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }

    /** 消息结构（role: system/user/assistant） */
    public record ChatMessage(String role, String content) {}
}
