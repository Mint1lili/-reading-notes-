package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.TraceContext;
import com.readingnotes.config.DoubaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 豆包大模型 API 客户端（火山引擎方舟 Ark · OpenAI 兼容端点）。
 *
 * <p>接口：
 * <ul>
 *   <li>Chat：{@code POST /chat/completions}（支持真流式 SSE）</li>
 *   <li>Embedding：{@code POST /embeddings}（文本模型）或 {@code POST /embeddings/multimodal}
 *       （doubao-embedding-vision-* 只暴露此端点），由 {@code doubao.embedding-mode} 控制，
 *       auto 模式下探测失败会自动切换并记住</li>
 * </ul>
 *
 * <p>接入要点（对齐官方文档 docs/82379/1302003）：
 * <ul>
 *   <li>{@code input} 不能是空列表，且每个元素不能是空字符串 —— 客户端统一兜底为单个空格；</li>
 *   <li>每个元素最大 4096 token、单条 ≤ 100000 字节 —— 超长文本按 {@code max-input-chars} 截断并告警；</li>
 *   <li>官方建议单次 ≤ 4 条以保证性能，服务端另有条数上限（不同模型/接入点不同）——
 *       因此按 {@code batch-size} 在客户端自行切批，遇到 "max N, got M" 类 400 时自动降到 N 并记住；</li>
 *   <li>{@code doubao-embedding-text-240715} 原生 2560 维，需靠 {@code dimensions} 降维；
 *       若该模型/接入点不接受此参数，客户端自动去掉参数重试一次，并由维度校验给出可执行提示。</li>
 * </ul>
 *
 * <p>重试策略：仅对 429 / 5xx / 连接超时 / IO 异常重试，指数退避 base×2^n（默认 1s→2s→4s），
 * 叠加 0~250ms 抖动，优先遵循服务端 {@code Retry-After}。401/403/400 等客户端错误不重试。
 * 安全：api-key 只来自环境变量，日志与响应中绝不输出 key。
 */
@Slf4j
@Component
public class DoubaoClient {

    /** 配置缺省或非法时的兜底批大小 */
    private static final int FALLBACK_BATCH_SIZE = 10;
    /** 向量化接口形态：文本端点 / 多模态端点 */
    private static final String MODE_TEXT = "text";
    private static final String MODE_MULTIMODAL = "multimodal";
    /** 单条错误响应最多打印/透传多少字符 */
    private static final int MAX_ERROR_BODY = 300;
    /** "max 10, got 16" / "maximum batch size is 10" 之类的服务端条数上限提示 */
    private static final Pattern BATCH_LIMIT_PATTERN =
            Pattern.compile("(?:max(?:imum)?[^0-9]{0,16}|up to[^0-9]{0,8})(\\d+)", Pattern.CASE_INSENSITIVE);

    private final DoubaoProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    /** 运行期实际批大小：配置值，遇到服务端上限自动下调并保留 */
    private final AtomicInteger effectiveBatchSize;
    /** 模型/接入点是否接受 dimensions 参数（首次被拒后置 false，后续不再尝试） */
    private final AtomicBoolean dimensionsParamSupported = new AtomicBoolean(true);
    /** 运行期确定的向量化接口形态：text / multimodal（null 表示按配置 auto 待定） */
    private final AtomicReference<String> resolvedEmbeddingMode = new AtomicReference<>();
    /** 模型是否接受 thinking 参数（首次被拒后置 false，后续不再发送） */
    private final AtomicBoolean thinkingParamSupported = new AtomicBoolean(true);

    public DoubaoClient(DoubaoProperties props) {
        this.props = props;
        int configured = props.getBatchSize() > 0 ? props.getBatchSize() : FALLBACK_BATCH_SIZE;
        this.effectiveBatchSize = new AtomicInteger(configured);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                // 方舟 SSE 在 HTTP/1.1 下最稳；HTTP/2 长连接偶发被中间设备切断
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ------------------------------------------------------------------ Chat

    /** Chat 非流式调用，返回完整文本 */
    public String chat(List<ChatMessage> messages, double temperature, int maxTokens) {
        return chatWithUsage(messages, temperature, maxTokens).content();
    }

    /** Chat 非流式调用，同时返回 token 用量（供 F5 用量统计） */
    public ChatResult chatWithUsage(List<ChatMessage> messages, double temperature, int maxTokens) {
        ensureKey();
        String body = buildChatBody(messages, temperature, maxTokens, false);
        JsonNode resp;
        try {
            resp = sendJson("/chat/completions", body,
                    timeoutOrDefault(props.getChatTimeoutSeconds(), 120), CallKind.CHAT, null);
        } catch (DoubaoHttpError e) {
            if (e.getStatus() == 400 && mentionsThinking(e.getRaw())) {
                log.warn("模型 {} 不接受 thinking 参数，改为默认行为 trace={}",
                        props.getChatModel(), TraceContext.current());
                thinkingParamSupported.set(false);
                resp = sendJson("/chat/completions", buildChatBody(messages, temperature, maxTokens, false),
                        timeoutOrDefault(props.getChatTimeoutSeconds(), 120), CallKind.CHAT, null);
            } else {
                throw e;
            }
        }
        JsonNode choice = resp.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (content.isEmpty()) {
            log.warn("doubao chat 返回空内容 finish_reason={} trace={}",
                    choice.path("finish_reason").asText(""), TraceContext.current());
        }
        return new ChatResult(content,
                resp.path("usage").path("prompt_tokens").asInt(0),
                resp.path("usage").path("completion_tokens").asInt(0));
    }

    /**
     * Chat 流式调用（SSE）。每收到一个 content 增量回调一次 onDelta。
     * <p>用 {@code BodyHandlers.ofLines()} 边读边回调，不会把整段回答缓冲到结束才返回。
     */
    public void chatStream(List<ChatMessage> messages, double temperature, int maxTokens,
                           Consumer<String> onDelta) {
        chatStream(messages, temperature, maxTokens, onDelta, null);
    }

    /**
     * Chat 流式调用，结束时回调完整结果（含 usage，若服务端在尾包返回）。
     *
     * @param onComplete 可空；流正常结束时回调一次
     */
    public void chatStream(List<ChatMessage> messages, double temperature, int maxTokens,
                           Consumer<String> onDelta, Consumer<ChatResult> onComplete) {
        doChatStream(messages, temperature, maxTokens, onDelta, onComplete, true);
    }

    /**
     * 流式对话的实际实现（SSE 逐行解析）。
     * <p>
     * 只处理 {@code data:} 行，忽略 {@code event:/id:/} 与空行；遇到 {@code [DONE]} 正常结束。
     * 若服务端拒绝 {@code thinking} 参数且 {@code allowThinkingRetry=true}，会关闭该参数整体重试一次
     * ——重试会重放整段回答，因此只在首个 delta 之前允许重试。
     *
     * @param onDelta    每收到增量文本回调一次（用于 SSE 转发给前端）
     * @param onComplete 正常结束时回调，携带全文与 usage
     */
    private void doChatStream(List<ChatMessage> messages, double temperature, int maxTokens,
                              Consumer<String> onDelta, Consumer<ChatResult> onComplete,
                              boolean allowThinkingRetry) {
        ensureKey();
        String body = buildChatBody(messages, temperature, maxTokens, true);
        HttpRequest req = baseRequest("/chat/completions")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(timeoutOrDefault(props.getStreamTimeoutSeconds(), 300)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        StringBuilder full = new StringBuilder();
        ChatResult[] usage = new ChatResult[1];
        try {
            HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                String raw;
                try (Stream<String> err = resp.body()) {
                    raw = err.limit(20).reduce("", (a, b) -> a + b);
                }
                throw mapStatus(resp.statusCode(), raw, CallKind.CHAT);
            }
            try (Stream<String> lines = resp.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (!line.startsWith("data:")) {
                        continue; // 忽略 event: / id: / 空行
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode node = mapper.readTree(payload);
                    if (node.hasNonNull("error")) {
                        String msg = node.path("error").path("message").asText("豆包流式调用失败");
                        log.error("doubao stream error payload={} trace={}", truncate(payload), TraceContext.current());
                        throw new BusinessException(ErrorCode.AI_UNAVAILABLE, msg);
                    }
                    String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                    JsonNode u = node.path("usage");
                    if (u.isObject()) {
                        usage[0] = new ChatResult(full.toString(),
                                u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0));
                    }
                }
            }
            if (onComplete != null) {
                onComplete.accept(usage[0] != null
                        ? usage[0]
                        : new ChatResult(full.toString(), 0, 0));
            }
        } catch (DoubaoHttpError e) {
            // 模型不接受 thinking 参数 → 去掉后重试一次（对推理类模型关闭思考，首字延迟从数十秒降到秒级）
            if (allowThinkingRetry && e.getStatus() == 400 && mentionsThinking(e.getRaw())) {
                log.warn("模型 {} 不接受 thinking 参数，改为默认行为 trace={}",
                        props.getChatModel(), TraceContext.current());
                thinkingParamSupported.set(false);
                doChatStream(messages, temperature, maxTokens, onDelta, onComplete, false);
                return;
            }
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.error("doubao stream timeout {}s trace={}",
                    timeoutOrDefault(props.getStreamTimeoutSeconds(), 300), TraceContext.current());
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 响应超时，请重试");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("doubao chat stream failed trace={}", TraceContext.current(), e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    // -------------------------------------------------------------- Embedding

    /** Embedding 批量调用（自动按 batch-size 切批），失败按策略重试 */
    public List<List<Float>> embed(List<String> texts) {
        return embed(texts, null);
    }

    /**
     * Embedding 批量调用，自动切批 + 重试。
     *
     * @param texts       待向量化文本（内部会自动切分为多次请求）
     * @param rateLimited 出参：本次调用是否遇到过 429（用于构建任务放慢节奏），可传 null
     * @return 与入参一一对应的向量，顺序保证一致
     */
    public List<List<Float>> embed(List<String> texts, boolean[] rateLimited) {
        ensureKey();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> prepared = new ArrayList<>(texts.size());
        for (String t : texts) {
            prepared.add(prepareText(sanitizeJsonControlChars(t)));
        }

        List<List<Float>> out = new ArrayList<>(prepared.size());
        int from = 0;
        while (from < prepared.size()) {
            int size = Math.max(1, effectiveBatchSize.get());
            int to = Math.min(prepared.size(), from + size);
            try {
                out.addAll(embedBatch(prepared.subList(from, to), rateLimited));
                from = to;
            } catch (BusinessException e) {
                // 服务端返回 "max 10, got 16" → 自适应降批后重试当前分片，不消耗重试预算
                int limit = e.getCode() == ErrorCode.DOUBAO_BATCH_TOO_LARGE.getCode()
                        ? parseBatchLimit(e.getMessage()) : -1;
                if (limit > 0 && limit < size) {
                    effectiveBatchSize.set(limit);
                    log.warn("豆包 embedding 单次条数上限为 {}，已自动下调批大小（原 {}）", limit, size);
                    continue;
                }
                throw e;
            }
        }
        if (out.size() != prepared.size()) {
            throw new BusinessException(ErrorCode.DOUBAO_DOWN,
                    "豆包向量化返回条数异常（请求 " + prepared.size() + "，返回 " + out.size() + "）");
        }
        return out;
    }

    /**
     * 清除 PDF 解析产物中的非法 JSON 控制字符（0x00-0x1F 中除 \t \n \r 外），
     * 防止 Jackson 原样输出导致方舟 "could not parse the JSON body"（70006）。
     * 仅影响向量化请求文本，不改变库内原文。
     */
    private static String sanitizeJsonControlChars(String t) {
        if (t == null || t.isEmpty()) {
            return t;
        }
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** 单批请求（已保证 ≤ 当前批大小），内部完成重试与接口形态自适应 */
    private List<List<Float>> embedBatch(List<String> batch, boolean[] rateLimited) {
        String mode = resolveEmbeddingMode();
        if (MODE_MULTIMODAL.equals(mode)) {
            return embedMultimodal(batch, rateLimited, true);
        }
        try {
            return embedText(batch, rateLimited);
        } catch (DoubaoHttpError e) {
            // auto 模式下：模型只提供 /embeddings/multimodal 时（404/400 且非「未开通」），自动切过去。
            // 两个端点都失败时钉住 text，避免每批都白跑一次探测；对外仍抛原始错误（更贴近配置问题）。
            if (isAutoMode() && shouldSwitchToMultimodal(e)) {
                log.warn("模型 {} 的 /embeddings 端点不可用（{}），尝试 /embeddings/multimodal trace={}",
                        props.getEmbeddingModel(), e.getStatus(), TraceContext.current());
                try {
                    List<List<Float>> vectors = embedMultimodal(batch, rateLimited, false);
                    resolvedEmbeddingMode.set(MODE_MULTIMODAL);
                    log.info("已切换为多模态向量化端点 model={} trace={}",
                            props.getEmbeddingModel(), TraceContext.current());
                    return vectors;
                } catch (BusinessException inner) {
                    resolvedEmbeddingMode.set(MODE_TEXT);
                    log.warn("多模态端点同样不可用：{}", inner.getMessage());
                    throw e;
                }
            }
            throw e;
        }
    }

    /** 文本向量化：POST /embeddings，input 为字符串数组 */
    private List<List<Float>> embedText(List<String> batch, boolean[] rateLimited) {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", props.getEmbeddingModel());
        node.set("input", mapper.valueToTree(batch));
        node.put("encoding_format", "float"); // 显式 float，避免个别接入点默认 base64
        if (dimensionsParamSupported.get() && props.getEmbeddingDim() > 0) {
            node.put("dimensions", props.getEmbeddingDim());
        }
        JsonNode resp = postEmbedding("/embeddings", node, rateLimited);
        return parseVectors(resp, batch.size(), batch, rateLimited);
    }

    /**
     * 多模态向量化：POST /embeddings/multimodal，input 为 [{type:text,text:...}]。
     * doubao-embedding-vision-* 只暴露这个端点（官方文档 82379/1554710）。
     */
    private List<List<Float>> embedMultimodal(List<String> batch, boolean[] rateLimited, boolean allowTextFallback) {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", props.getEmbeddingModel());
        ArrayNode input = mapper.createArrayNode();
        for (String text : batch) {
            input.addObject().put("type", "text").put("text", text);
        }
        node.set("input", input);
        node.put("encoding_format", "float");
        String instruction = props.getEmbeddingInstruction();
        if (instruction != null && !instruction.isBlank()) {
            node.put("instructions", instruction);
        }
        if (dimensionsParamSupported.get() && props.getEmbeddingDim() > 0) {
            node.put("dimensions", props.getEmbeddingDim());
        }
        JsonNode resp;
        try {
            resp = postEmbedding("/embeddings/multimodal", node, rateLimited);
        } catch (DoubaoHttpError e) {
            if (allowTextFallback && isAutoMode()
                    && (e.getStatus() == 404 || e.getStatus() == 400) && !isModelNotOpen(e.getRaw())) {
                log.warn("模型 {} 不支持 /embeddings/multimodal，回退到 /embeddings trace={}",
                        props.getEmbeddingModel(), TraceContext.current());
                resolvedEmbeddingMode.set(MODE_TEXT);
                return embedText(batch, rateLimited);
            }
            throw e;
        }
        // 批量请求在多模态端点可能只返回单对象 data，parseVectors 会自动退回逐条（postSingle）
        return parseVectors(resp, batch.size(), batch, rateLimited);
    }

    /**
     * 发送向量化请求，并处理「不支持 dimensions 参数」的回退：
     * 400 且报错提到 dimension → 去掉参数重试一次并记住。
     */
    private JsonNode postEmbedding(String path, ObjectNode node, boolean[] rateLimited) {
        int timeout = timeoutOrDefault(props.getEmbeddingTimeoutSeconds(), 35);
        try {
            return sendJson(path, mapper.writeValueAsString(node), timeout, CallKind.EMBED, rateLimited);
        } catch (DoubaoHttpError e) {
            boolean sentDimensions = node.has("dimensions");
            if (sentDimensions && e.getStatus() == 400 && mentionsDimension(e.getRaw())) {
                dimensionsParamSupported.set(false);
                log.warn("豆包 embedding 不接受 dimensions 参数，改用模型默认维度；"
                        + "请确认 doubao.embedding-dim 与模型实际维度一致 trace={}", TraceContext.current());
                node.remove("dimensions");
                try {
                    return sendJson(path, mapper.writeValueAsString(node), timeout, CallKind.EMBED, rateLimited);
                } catch (Exception inner) {
                    log.warn("去除 dimensions 后重试仍失败：{}", inner.getMessage());
                    throw e;
                }
            }
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DOUBAO_BAD_REQUEST, "向量化请求体构造失败：" + e.getMessage());
        }
    }

    /**
     * 解析向量化响应。方舟不同端点/模型的返回结构不一致，这里做兼容：
     * <ul>
     *   <li>文本端点：{@code {"data":[{"embedding":[...],"index":0}]}}</li>
     *   <li>多模态端点：{@code {"data":{"embedding":[...]}}} 或 {@code {"data":[{"embedding":[...]}]}}</li>
     * </ul>
     * 条数对不上时，按 {@code embedding-sequential-fallback} 退回逐条请求，保证结果正确。
     */
    private List<List<Float>> parseVectors(JsonNode resp, int expected, List<String> batch, boolean[] rateLimited) {
        JsonNode data = resp.path("data");
        if (data.isObject()) {
            // 单条包装形态 → 拆成数组
            JsonNode inner = data.path("data");
            if (inner.isArray()) {
                data = inner;
            } else if (data.path("embedding").isArray()) {
                ArrayNode arr = mapper.createArrayNode();
                arr.add(data);
                data = arr;
            }
        }
        if (!data.isArray() && resp.path("embedding").isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            ObjectNode item = arr.addObject();
            item.set("embedding", resp.path("embedding"));
            data = arr;
        }
        if (!data.isArray() || data.size() == 0) {
            log.error("embedding 响应缺少 data trace={} body={}", TraceContext.current(), truncate(resp.toString()));
            throw new BusinessException(ErrorCode.DOUBAO_DOWN, "豆包向量化返回结构异常（缺少 data）");
        }
        if (data.size() != expected) {
            if (props.isEmbeddingSequentialFallback() && expected > 1) {
                log.warn("向量化返回 {} 条，请求 {} 条，退回逐条请求 trace={}",
                        data.size(), expected, TraceContext.current());
                List<List<Float>> out = new ArrayList<>(expected);
                for (String text : batch) {
                    out.add(parseVectors(postSingle(text, rateLimited), 1, List.of(text), rateLimited).get(0));
                }
                return out;
            }
            log.error("embed size mismatch input={} output={} trace={}",
                    expected, data.size(), TraceContext.current());
            throw new BusinessException(ErrorCode.DOUBAO_DOWN,
                    "豆包向量化返回条数与请求不一致（请求 " + expected + "，返回 " + data.size() + "）");
        }

        List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(expected, null));
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            JsonNode emb = item.path("embedding");
            if (!emb.isArray()) {
                emb = item.path("data").path("embedding");
            }
            int pos = item.has("index") ? item.path("index").asInt(fallbackIndex) : fallbackIndex;
            if (pos < 0 || pos >= expected || vectors.get(pos) != null) {
                pos = fallbackIndex;
            }
            if (pos < 0 || pos >= expected) {
                pos = fallbackIndex;
            }
            vectors.set(pos, toVector(emb));
            fallbackIndex++;
        }
        if (vectors.contains(null)) {
            throw new BusinessException(ErrorCode.DOUBAO_DOWN, "豆包向量化返回数据不完整");
        }
        return vectors;
    }

    /** 逐条请求单文本（仅用于返回条数异常的兜底与 multimodal 逐条向量化） */
    private JsonNode postSingle(String text, boolean[] rateLimited) {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", props.getEmbeddingModel());
        if (MODE_MULTIMODAL.equals(resolveEmbeddingMode())) {
            // 注意：createArrayNode().addObject() 返回 ObjectNode，必须先用变量持有数组再 set
            ArrayNode arr = mapper.createArrayNode();
            arr.addObject().put("type", "text").put("text", text);
            node.set("input", arr);
            node.put("encoding_format", "float");
            String instruction = props.getEmbeddingInstruction();
            if (instruction != null && !instruction.isBlank()) {
                node.put("instructions", instruction);
            }
            if (dimensionsParamSupported.get() && props.getEmbeddingDim() > 0) {
                node.put("dimensions", props.getEmbeddingDim());
            }
            return postEmbedding("/embeddings/multimodal", node, rateLimited);
        }
        node.set("input", mapper.createArrayNode().add(text));
        node.put("encoding_format", "float");
        if (dimensionsParamSupported.get() && props.getEmbeddingDim() > 0) {
            node.put("dimensions", props.getEmbeddingDim());
        }
        return postEmbedding("/embeddings", node, rateLimited);
    }

    /**
     * 当前生效的向量化端点形态。
     * <p>
     * 优先用探测后记住的结果（{@code resolvedEmbeddingMode}），没有则按配置决定：
     * 显式 text/multimodal 直接返回，auto 首次一律先试文本端点，失败后再切换。
     */
    private String resolveEmbeddingMode() {
        String resolved = resolvedEmbeddingMode.get();
        if (resolved != null) {
            return resolved;
        }
        String configured = props.getEmbeddingMode() == null ? "auto" : props.getEmbeddingMode().trim().toLowerCase(Locale.ROOT);
        if (MODE_MULTIMODAL.equals(configured) || MODE_TEXT.equals(configured)) {
            return configured;
        }
        return MODE_TEXT; // auto 首次按文本端点探测
    }

    /** 配置是否为 auto（既非 text 也非 multimodal），只有 auto 才允许运行时切换端点 */
    private boolean isAutoMode() {
        String configured = props.getEmbeddingMode() == null ? "auto" : props.getEmbeddingMode().trim().toLowerCase(Locale.ROOT);
        return !MODE_TEXT.equals(configured) && !MODE_MULTIMODAL.equals(configured);
    }

    /** 是否应因「该模型没有 /embeddings 文本端点」而切换到多模态端点 */
    private boolean shouldSwitchToMultimodal(DoubaoHttpError e) {
        if (e.getStatus() != 404 && e.getStatus() != 400) {
            return false;
        }
        if (isModelNotOpen(e.getRaw())) {
            return false; // 未开通时换端点也没用，直接暴露开通提示
        }
        // 条数超限、dimensions 不受支持都有各自的处理路径，不能误判成端点不匹配
        if (isBatchLimitError(e.getRaw()) || mentionsDimension(e.getRaw())) {
            return false;
        }
        return true;
    }

    /** 当前实际使用的向量化端点形态（text / multimodal） */
    public String getEmbeddingMode() {
        return resolveEmbeddingMode();
    }

    /**
     * JSON 数组 → 向量，并在此处做维度校验。
     * <p>
     * 维度不一致是最常见的配置错误：Chroma 集合按首次写入维度建索引，
     * 后续写入不同维度会静默失败或直接 400，所以在客户端提前拦住并给出可操作的修复指引。
     */
    private List<Float> toVector(JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) {
            throw new BusinessException(ErrorCode.DOUBAO_DOWN, "豆包向量化返回空向量");
        }
        List<Float> vector = new ArrayList<>(arr.size());
        for (JsonNode v : arr) {
            vector.add((float) v.asDouble());
        }
        int expected = props.getEmbeddingDim();
        if (expected > 0 && vector.size() != expected) {
            log.error("embedding dim mismatch actual={} expected={} model={}",
                    vector.size(), expected, props.getEmbeddingModel());
            throw new BusinessException(ErrorCode.DIM_MISMATCH,
                    "豆包向量维度为 " + vector.size() + "，与配置 embedding-dim=" + expected
                            + " 不一致。请把 doubao.embedding-dim 改为 " + vector.size()
                            + " 并重建知识库（或换用支持 dimensions 降维的模型/接入点）");
        }
        return vector;
    }

    // ------------------------------------------------------------------ 探针

    /**
     * 健康探针：用一条极短文本真实调用 /embeddings，确认 key、网络、模型与维度是否可用。
     * <p>不抛异常，返回结构化结果，供运维面板展示。
     */
    public Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key_configured", hasKey());
        out.put("base_url", props.getBaseUrl());
        out.put("chat_model", props.getChatModel());
        out.put("embedding_model", props.getEmbeddingModel());
        out.put("expected_dim", props.getEmbeddingDim());
        out.put("batch_size", effectiveBatchSize.get());
        out.put("embedding_mode", resolveEmbeddingMode());
        if (!hasKey()) {
            out.put("status", "DOWN");
            out.put("error", "未配置 DOUBAO_API_KEY");
            return out;
        }
        long start = System.currentTimeMillis();
        try {
            List<Float> v = embedBatch(List.of("ping"), null).get(0);
            out.put("status", "UP");
            out.put("actual_dim", v.size());
        } catch (BusinessException e) {
            out.put("status", "DOWN");
            out.put("error_code", e.getCode());
            out.put("error", e.getMessage());
        } catch (Exception e) {
            out.put("status", "DOWN");
            out.put("error", e.getMessage());
        }
        out.put("latency_ms", System.currentTimeMillis() - start);
        out.put("chat_probe", probeChat());
        return out;
    }

    /** 对话模型探针：与 embedding 分开报告，便于区分「模型没开」和「维度不对」 */
    private Map<String, Object> probeChat() {
        Map<String, Object> chat = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        try {
            ChatResult r = chatWithUsage(List.of(new ChatMessage("user", "回复两个字：可用")), 0.1, 16);
            chat.put("status", "UP");
            chat.put("reply", truncate(r.content()));
        } catch (BusinessException e) {
            chat.put("status", "DOWN");
            chat.put("error_code", e.getCode());
            chat.put("error", e.getMessage());
        } catch (Exception e) {
            chat.put("status", "DOWN");
            chat.put("error", String.valueOf(e.getMessage()));
        }
        chat.put("latency_ms", System.currentTimeMillis() - t0);
        return chat;
    }

    /** key 是否已配置（只判空，不校验有效性；有效性由 {@link #probe()} 负责） */
    public boolean hasKey() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /** 运行期实际批大小（可能因服务端上限被自动下调） */
    public int getEffectiveBatchSize() {
        return effectiveBatchSize.get();
    }

    // ------------------------------------------------------------- HTTP 基础

    private JsonNode sendJson(String path, String body, int timeoutSeconds,
                              CallKind kind, boolean[] rateLimited) {
        int maxRetries = Math.max(0, props.getMaxRetries());
        int attempt = 0;
        while (true) {
            HttpRequest req = baseRequest(path)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status == 200) {
                    return mapper.readTree(resp.body());
                }
                boolean retryable = status == 429 || status >= 500;
                if (retryable && attempt < maxRetries) {
                    attempt++;
                    if (status == 429 && rateLimited != null) {
                        rateLimited[0] = true;
                    }
                    long retryAfter = retryAfterSeconds(resp);
                    log.warn("doubao {} http {} 可重试，第 {}/{} 次退避{}ms trace={}",
                            kind, status, attempt, maxRetries,
                            retryAfter > 0 ? retryAfter * 1000 : -1, TraceContext.current());
                    sleepBackoff(attempt, retryAfter);
                    continue;
                }
                throw mapStatus(status, resp.body(), kind);
            } catch (HttpTimeoutException e) {
                if (attempt < maxRetries) {
                    attempt++;
                    log.warn("doubao {} 请求超时 {}s，第 {}/{} 次重试 trace={}",
                            kind, timeoutSeconds, attempt, maxRetries, TraceContext.current());
                    sleepBackoff(attempt, -1);
                    continue;
                }
                log.error("doubao {} timeout {}s trace={}", kind, timeoutSeconds, TraceContext.current());
                throw kind == CallKind.EMBED
                        ? new BusinessException(ErrorCode.EMBED_TIMEOUT)
                        : new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 响应超时，请重试");
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    attempt++;
                    log.warn("doubao {} 网络异常：{}，第 {}/{} 次重试 trace={}",
                            kind, e.getMessage(), attempt, maxRetries, TraceContext.current());
                    sleepBackoff(attempt, -1);
                    continue;
                }
                log.error("doubao {} io failed trace={}", kind, TraceContext.current(), e);
                throw kind == CallKind.EMBED
                        ? new BusinessException(ErrorCode.DOUBAO_DOWN, "无法连接豆包 API，请检查网络与 base-url")
                        : new BusinessException(ErrorCode.AI_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw kind == CallKind.EMBED
                        ? new BusinessException(ErrorCode.EMBED_TIMEOUT)
                        : new BusinessException(ErrorCode.AI_UNAVAILABLE);
            }
        }
    }

    /** HTTP 非 200 → 业务异常（区分鉴权、模型、限流、参数、服务端故障） */
    private BusinessException mapStatus(int status, String raw, CallKind kind) {
        String body = truncate(raw);
        log.error("doubao {} http status={} body={} trace={}", kind, status, body, TraceContext.current());
        ErrorCode code;
        String detail = extractMessage(raw);
        if (status == 401 || status == 403) {
            code = ErrorCode.DOUBAO_AUTH_FAILED;
        } else if (status == 404) {
            // 404 有两种语义：模型名/接入点写错（NotFound）与「账号未开通」（ModelNotOpen）
            // 后者是配置正确但缺少开通动作，提示必须能直接指导用户操作
            code = raw != null && raw.contains("ModelNotOpen")
                    ? ErrorCode.DOUBAO_MODEL_NOT_OPEN
                    : ErrorCode.DOUBAO_MODEL_NOT_FOUND;
        } else if (status == 429) {
            code = ErrorCode.DOUBAO_RATE_LIMITED;
        } else if (status == 400) {
            code = isBatchLimitError(raw) ? ErrorCode.DOUBAO_BATCH_TOO_LARGE : ErrorCode.DOUBAO_BAD_REQUEST;
        } else if (kind == CallKind.EMBED) {
            code = ErrorCode.DOUBAO_DOWN;
        } else {
            code = ErrorCode.AI_UNAVAILABLE;
        }
        String message = detail.isEmpty() ? code.getMessage() : code.getMessage() + "：" + detail;
        return new DoubaoHttpError(code, message, status, raw);
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey());
    }

    /**
     * 组装对话请求体。
     * <p>
     * 三个开关按"服务端是否支持"逐步降级，对应 {@code xxxParamSupported} 标记：
     * {@code max_tokens}（>0 才发）、{@code stream_options.include_usage}（默认不发）、
     * {@code thinking}（auto 不发）。任一参数被服务端拒绝后，标记置 false 并重试。
     */
    private String buildChatBody(List<ChatMessage> messages, double temperature, int maxTokens, boolean stream) {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", props.getChatModel());
        node.put("temperature", temperature);
        if (maxTokens > 0) {
            node.put("max_tokens", maxTokens);
        }
        if (stream) {
            node.put("stream", true);
            // include_usage 并非所有模型/接入点都支持，默认不发；需要统计时再打开
            if (props.isStreamIncludeUsage()) {
                node.put("stream_options", mapper.createObjectNode().put("include_usage", true));
            }
        }
        // 推理类模型（seed-2.x turbo/pro）默认开启深度思考，首字可能等数十秒；
        // 阅读问答场景不需要，按配置显式关闭，不支持该参数的模型会自动回退。
        String thinking = props.getThinking();
        if (thinkingParamSupported.get() && thinking != null && !thinking.isBlank()
                && !"auto".equalsIgnoreCase(thinking.trim())) {
            node.putObject("thinking").put("type", thinking.trim());
        }
        node.set("messages", mapper.valueToTree(messages));
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "请求体构造失败");
        }
    }

    /**
     * 输入预处理：
     * ① 空白文本会被官方接口拒绝（"列表的每个成员不能为空字符串"），统一替换为单个空格；
     * ② 超长文本截断，避免 4096 token 限制直接 400。
     */
    private String prepareText(String raw) {
        if (raw == null || raw.isBlank()) {
            return " ";
        }
        String text = raw.strip();
        int maxChars = props.getMaxInputChars();
        if (maxChars > 0 && text.length() > maxChars) {
            log.warn("embedding 输入超长 {} 字符，已截断到 {} trace={}",
                    text.length(), maxChars, TraceContext.current());
            text = text.substring(0, maxChars);
        }
        return text;
    }

    /** 指数退避 base×2^(attempt-1)，上限 retry-max-ms；retryAfter>0 时优先用它（上限一致） */
    private void sleepBackoff(int attempt, long retryAfterSeconds) {
        long base = Math.max(50, props.getRetryBaseMs());
        long cap = Math.max(base, props.getRetryMaxMs());
        long delay = Math.min(cap, base * (1L << Math.min(attempt - 1, 10)));
        if (retryAfterSeconds > 0) {
            delay = Math.min(cap, retryAfterSeconds * 1000L);
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, 251);
        long total = delay + jitter;
        log.warn("embedding/chat 退避 {}ms（第 {} 次，上限 {}ms）", total, attempt, cap);
        try {
            Thread.sleep(total);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EMBED_TIMEOUT);
        }
    }

    /** 解析 Retry-After（秒或 HTTP 日期）；无法解析返回 -1 */
    private long retryAfterSeconds(HttpResponse<?> resp) {
        String header = resp.headers().firstValue("Retry-After").orElse(null);
        if (header == null || header.isBlank()) {
            return -1;
        }
        String h = header.trim();
        try {
            return Math.max(0, Long.parseLong(h));
        } catch (NumberFormatException ignored) {
            // HTTP-date 形式
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = when.toInstant().toEpochMilli() / 1000 - System.currentTimeMillis() / 1000;
            return Math.max(0, seconds);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 从错误文案里解析服务端允许的单批上限，如 "max 10 per request" → 10。
     *
     * @return 合法上限（1~256）；解析不出或超出合理范围返回 -1
     */
    private int parseBatchLimit(String message) {
        if (message == null) {
            return -1;
        }
        Matcher m = BATCH_LIMIT_PATTERN.matcher(message);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                return v >= 1 && v <= 256 ? v : -1;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 是否为"单批条数超限"错误：需同时出现超限语义、输入语义，且能解析出具体上限。
     * <p>
     * 三个条件缺一不可，否则容易把"维度不支持""端点不存在"误判成批大小问题，
     * 导致错误地缩小批次而不是切换端点。
     */
    private boolean isBatchLimitError(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean mentionsLimit = lower.contains("too many") || lower.contains("exceed")
                || (lower.contains("max") && lower.contains("got"));
        boolean mentionsInput = lower.contains("batch") || lower.contains("input") || lower.contains("size");
        return mentionsLimit && mentionsInput && parseBatchLimit(raw) > 0;
    }

    /** 错误信息是否指向 dimensions 参数（此时应关掉降维参数重试，而不是换端点） */
    private boolean mentionsDimension(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT).contains("dimension");
    }

    /** 错误信息是否指向 thinking 参数（此时应去掉该参数重试） */
    private boolean mentionsThinking(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT).contains("thinking");
    }

    /** 服务端是否明确表示「模型未开通」（此时换端点/重试都无意义，应提示去控制台开通） */
    private boolean isModelNotOpen(String raw) {
        return raw != null && raw.contains("ModelNotOpen");
    }

    /** 提取服务端 error.message，取不到则返回空串 */
    private String extractMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(raw);
            String msg = node.path("error").path("message").asText("");
            if (!msg.isEmpty()) {
                return truncate(msg);
            }
            String message = node.path("message").asText("");
            if (!message.isEmpty()) {
                return truncate(message);
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体
        }
        return truncate(raw);
    }

    /** 调用前校验 key，缺失时抛 70004 并直接提示配置哪个环境变量 */
    private void ensureKey() {
        if (!hasKey()) {
            throw new BusinessException(ErrorCode.DOUBAO_AUTH_FAILED,
                    "未配置豆包 API Key，请设置环境变量 DOUBAO_API_KEY 后重启服务");
        }
    }

    /** 取 key（已确保非空，调用方需先 ensureKey） */
    private String apiKey() {
        return props.getApiKey().trim();
    }

    /** 超时配置兜底：配置为 0/负数时用调用方给的默认值 */
    private int timeoutOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /** 去掉 baseUrl 末尾斜杠；未配置时回落到方舟默认地址，避免拼出双斜杠 */
    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://ark.cn-beijing.volces.com/api/v3";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 截断错误体，避免整段 HTML/JSON 打进日志与响应 */
    private String truncate(String s) {
        return s == null || s.length() <= MAX_ERROR_BODY ? s : s.substring(0, MAX_ERROR_BODY) + "...";
    }

    private enum CallKind {EMBED, CHAT}

    /** 带原始响应体的业务异常，供 dimensions / 批上限等自适应逻辑判断 */
    private static class DoubaoHttpError extends BusinessException {
        private final int status;
        private final String raw;

        DoubaoHttpError(ErrorCode code, String message, int status, String raw) {
            super(code, message);
            this.status = status;
            this.raw = raw;
        }

        int getStatus() {
            return status;
        }

        String getRaw() {
            return raw;
        }
    }

    /** 消息结构（role: system/user/assistant） */
    public record ChatMessage(String role, String content) {
    }

    /** Chat 结果 + token 用量 */
    public record ChatResult(String content, int promptTokens, int completionTokens) {
    }
}
