package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.DoubaoProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DoubaoClient 接入层单测：用本地 HttpServer 精确模拟方舟 /embeddings 与 /chat/completions 的行为，
 * 覆盖分批、限流重试、错误分类、自适应降批、dimensions 回退、维度校验与 SSE 流式解析。
 * 不依赖网络与真实 API Key。
 */
class DoubaoClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private DoubaoClient client;

    private final List<JsonNode> embedRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger embedCalls = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();

    /** 注入故障：非 0 时前 failTimes 次请求返回 failStatus */
    private volatile int failStatus = 0;
    private volatile int failTimes = 0;
    private volatile String failBody = "";
    /** 服务端单次条数上限：>0 时超出即返回 400（模拟 "max N, got M"） */
    private volatile int serverMaxBatch = 0;
    /** 返回向量维度 */
    private volatile int serverDim = 1024;
    /** 是否接受 dimensions 参数（false 时返回 400，模拟部分接入点不支持降维） */
    private volatile boolean supportDimensions = true;
    /** 文本端点 /embeddings 是否可用（false 时返回 404，模拟 vision 模型只提供 multimodal 端点） */
    private volatile boolean textEndpointAvailable = true;
    /** 多模态端点返回结构：object（{"data":{"embedding":[...]}}）或 array */
    private volatile String multimodalShape = "array";
    private final AtomicInteger multimodalCalls = new AtomicInteger();
    private final List<JsonNode> multimodalRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/embeddings/multimodal", this::handleMultimodal);
        server.createContext("/api/v3/embeddings", this::handleEmbedding);
        server.createContext("/api/v3/chat/completions", this::handleChat);
        serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.start();
        client = new DoubaoClient(props(10, 1024));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------- 测试用例

    @Test
    @DisplayName("超过批大小自动切批，结果顺序与入参一致")
    void batchesByConfiguredSize() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            texts.add("第 " + i + " 段文本");
        }

        List<List<Float>> vectors = client.embed(texts);

        assertEquals(25, vectors.size());
        assertEquals(3, embedCalls.get(), "25 条 / 批大小 10 应为 3 次请求");
        assertEquals(List.of(10, 10, 5), batchSizes());
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals(1024, vectors.get(i).size());
            // 服务端按 index 回写，顺序必须与入参严格对应
            assertEquals(hashVector(texts.get(i), 1024).get(0), vectors.get(i).get(0), 1e-6f);
        }
    }

    @Test
    @DisplayName("429 触发指数退避重试，并置位 rateLimited 供构建任务降速")
    void retriesOn429() {
        failStatus = 429;
        failTimes = 1;
        failBody = "{\"error\":{\"message\":\"rate limit exceeded\"}}";
        boolean[] rateLimited = new boolean[1];

        List<List<Float>> vectors = client.embed(List.of("限流测试"), rateLimited);

        assertEquals(1, vectors.size());
        assertEquals(2, embedCalls.get(), "429 后应重试一次");
        assertTrue(rateLimited[0], "遇到 429 必须置位 rateLimited");
    }

    @Test
    @DisplayName("5xx 可重试，重试耗尽后返回 DOUBAO_DOWN")
    void givesUpAfterRetries() {
        failStatus = 500;
        failTimes = 99;

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.embed(List.of("服务端故障")));

        assertEquals(ErrorCode.DOUBAO_DOWN.getCode(), e.getCode());
        assertEquals(4, embedCalls.get(), "max-retries=3 → 首次 + 3 次重试");
    }

    @Test
    @DisplayName("401 不重试，直接给出 key 配置提示")
    void authFailureNotRetried() {
        failStatus = 401;
        failTimes = 99;
        failBody = "{\"error\":{\"message\":\"authentication failed\"}}";

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.embed(List.of("鉴权失败")));

        assertEquals(ErrorCode.DOUBAO_AUTH_FAILED.getCode(), e.getCode());
        assertEquals(1, embedCalls.get(), "鉴权错误重试没有意义");
        assertTrue(e.getMessage().contains("DOUBAO_API_KEY"));
    }

    @Test
    @DisplayName("404 提示模型或接入点不存在")
    void modelNotFound() {
        failStatus = 404;
        failTimes = 99;
        failBody = "{\"error\":{\"message\":\"model not found\"}}";

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.embed(List.of("模型不存在")));

        assertEquals(ErrorCode.DOUBAO_MODEL_NOT_FOUND.getCode(), e.getCode());
        assertEquals(1, embedCalls.get());
    }

    @Test
    @DisplayName("服务端条数上限小于配置时自动降批并记住")
    void shrinksBatchWhenServerLimitSmaller() {
        serverMaxBatch = 4;
        client = new DoubaoClient(props(10, 1024));

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            texts.add("文本-" + i);
        }
        List<List<Float>> vectors = client.embed(texts);

        assertEquals(9, vectors.size());
        assertEquals(4, client.getEffectiveBatchSize(), "应把批大小下调到服务端上限 4");
        // 1 次被拒 + 4/4/1 三次成功
        assertEquals(4, embedCalls.get());
        assertEquals(List.of(9, 4, 4, 1), batchSizes());
    }

    @Test
    @DisplayName("返回维度与配置不符时给出可执行的 DIM_MISMATCH 提示")
    void reportsDimensionMismatch() {
        serverDim = 2560;

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.embed(List.of("维度不符")));

        assertEquals(ErrorCode.DIM_MISMATCH.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("2560"), "提示里应包含实际维度");
        assertTrue(e.getMessage().contains("embedding-dim"), "提示里应指出要改哪个配置");
    }

    @Test
    @DisplayName("接入点不支持 dimensions 时自动去掉参数重试")
    void fallsBackWhenDimensionsUnsupported() {
        supportDimensions = false;
        serverDim = 2560;              // 模型原生 2560 维
        client = new DoubaoClient(props(10, 2560));

        List<List<Float>> vectors = client.embed(List.of("降维回退"));

        assertEquals(2560, vectors.get(0).size());
        assertEquals(2, embedCalls.get(), "第一次被 400 拒绝，去掉 parameters 后重试成功");
        assertTrue(embedRequests.get(0).has("dimensions"));
        assertFalse(embedRequests.get(1).has("dimensions"), "重试请求不应再带 dimensions");
    }

    @Test
    @DisplayName("空字符串入参兜底为单空格，不触发官方『成员不能为空』报错")
    void blankTextsAreNormalized() {
        client.embed(List.of("", "   ", "正常文本"));

        JsonNode input = embedRequests.get(0).path("input");
        assertEquals(" ", input.get(0).asText());
        assertEquals(" ", input.get(1).asText());
        assertEquals("正常文本", input.get(2).asText());
    }

    @Test
    @DisplayName("流式对话逐块回调并汇总完整内容")
    void streamsSseDeltas() {
        List<String> deltas = new ArrayList<>();
        DoubaoClient.ChatResult[] done = new DoubaoClient.ChatResult[1];

        client.chatStream(
                List.of(new DoubaoClient.ChatMessage("user", "你好")),
                0.3, 128, deltas::add, r -> done[0] = r);

        assertEquals(List.of("你好", "，", "世界"), deltas);
        assertNotNull(done[0]);
        assertEquals("你好，世界", done[0].content());
        assertEquals(7, done[0].promptTokens());
        assertEquals(3, done[0].completionTokens());
    }

    @Test
    @DisplayName("未配置 key 时快速失败，不发请求")
    void missingKeyFailsFast() {
        DoubaoProperties p = props(10, 1024);
        p.setApiKey("  ");
        DoubaoClient noKey = new DoubaoClient(p);

        BusinessException e = assertThrows(BusinessException.class,
                () -> noKey.embed(List.of("x")));

        assertEquals(ErrorCode.DOUBAO_AUTH_FAILED.getCode(), e.getCode());
        assertEquals(0, embedCalls.get());
    }

    @Test
    @DisplayName("健康探针在服务正常时返回 UP 与实际维度")
    void probeReportsUp() {
        Map<String, Object> probe = client.probe();

        assertEquals("UP", probe.get("status"));
        assertEquals(1024, probe.get("actual_dim"));
        assertEquals(true, probe.get("key_configured"));
        assertNotNull(probe.get("latency_ms"));
    }

    // ------------------------------------------------------------- 多模态端点

    @Test
    @DisplayName("auto 模式：文本端点 404 时自动切到 multimodal 并保持后续请求")
    void autoSwitchesToMultimodal() {
        textEndpointAvailable = false;

        List<List<Float>> vectors = client.embed(List.of("第一段", "第二段", "第三段"));

        assertEquals(3, vectors.size());
        assertEquals(1024, vectors.get(0).size());
        assertEquals("multimodal", client.getEmbeddingMode());
        assertEquals(1, multimodalCalls.get());
        JsonNode req = multimodalRequests.get(0);
        assertEquals("text", req.path("input").get(0).path("type").asText(), "多模态入参须是 {type,text} 对象");
        assertEquals("第一段", req.path("input").get(0).path("text").asText());
        assertEquals(1024, req.path("dimensions").asInt(), "vision 模型靠 dimensions 降到 1024");
    }

    @Test
    @DisplayName("显式 multimodal 模式：直接走多模态端点，不探测文本端点")
    void explicitMultimodalMode() {
        textEndpointAvailable = false;
        DoubaoProperties p = props(10, 1024);
        p.setEmbeddingMode("multimodal");
        client = new DoubaoClient(p);

        assertEquals(2, client.embed(List.of("a", "b")).size());
        assertEquals(0, embedCalls.get(), "显式 multimodal 不应再打文本端点");
        assertEquals(1, multimodalCalls.get());
    }

    @Test
    @DisplayName("多模态端点返回 {data:{embedding:[...]}} 单条包装也能解析")
    void multimodalObjectResponse() {
        multimodalShape = "object";
        DoubaoProperties p = props(10, 1024);
        p.setEmbeddingMode("multimodal");
        client = new DoubaoClient(p);

        List<List<Float>> vectors = client.embed(List.of("单条文本"));

        assertEquals(1, vectors.size());
        assertEquals(1024, vectors.get(0).size());
    }

    @Test
    @DisplayName("模型未开通时不盲目切换端点，直接给出开通指引")
    void modelNotOpenDoesNotSwitch() {
        failStatus = 404;
        failTimes = 99;
        failBody = "{\"error\":{\"code\":\"ModelNotOpen\",\"message\":\"Your account has not activated "
                + "the model doubao-embedding-vision-251215. Please activate the model service\"}}";

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.embed(List.of("未开通")));

        assertEquals(ErrorCode.DOUBAO_MODEL_NOT_OPEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("开通"), "提示里应告诉用户去控制台开通");
        assertEquals(0, multimodalCalls.get(), "未开通时换端点没有意义");
    }

    // --------------------------------------------------------------- 模拟服务

    private void handleEmbedding(HttpExchange ex) throws IOException {
        int call = embedCalls.incrementAndGet();
        JsonNode req = MAPPER.readTree(ex.getRequestBody());
        embedRequests.add(req);
        JsonNode input = req.path("input");
        int size = input.size();

        if (failTimes > 0 && call <= failTimes) {
            Map<String, String> headers = Map.of();
            send(ex, failStatus, failBody.isEmpty()
                    ? "{\"error\":{\"message\":\"injected failure\"}}" : failBody, headers);
            return;
        }
        if (!textEndpointAvailable) {
            send(ex, 404, "{\"error\":{\"code\":\"InvalidEndpointOrModel.NotFound\","
                    + "\"message\":\"The model or endpoint does not exist\"}}", Map.of());
            return;
        }
        if (serverMaxBatch > 0 && size > serverMaxBatch) {
            send(ex, 400, "{\"error\":{\"message\":\"invalid input: batch size " + size
                    + " exceeds maximum " + serverMaxBatch + ", got " + size + "\"}}", Map.of());
            return;
        }
        if (!supportDimensions && req.has("dimensions")) {
            send(ex, 400, "{\"error\":{\"message\":\"invalid request: unknown field dimensions\"}}", Map.of());
            return;
        }

        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            List<Float> vec = hashVector(input.get(i).asText(), serverDim);
            for (int j = 0; j < vec.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(vec.get(j));
            }
            sb.append("]}");
        }
        sb.append("],\"usage\":{\"prompt_tokens\":").append(size).append(",\"total_tokens\":")
                .append(size).append("}}");
        send(ex, 200, sb.toString(), Map.of());
    }

    /** 模拟 /embeddings/multimodal：input 为 [{type:text,text:...}]，支持 array / object 两种返回结构 */
    private void handleMultimodal(HttpExchange ex) throws IOException {
        int call = multimodalCalls.incrementAndGet();
        JsonNode req = MAPPER.readTree(ex.getRequestBody());
        multimodalRequests.add(req);
        if (failTimes > 0 && call <= failTimes) {
            send(ex, failStatus, failBody.isEmpty()
                    ? "{\"error\":{\"message\":\"injected failure\"}}" : failBody, Map.of());
            return;
        }
        JsonNode input = req.path("input");
        int size = input.size();
        List<String> texts = new ArrayList<>();
        for (JsonNode item : input) {
            texts.add(item.isObject() ? item.path("text").asText("") : item.asText(""));
        }
        if ("object".equals(multimodalShape)) {
            send(ex, 200, "{\"data\":{\"embedding\":" + jsonVector(hashVector(texts.get(0), serverDim)) + "}}",
                    Map.of());
            return;
        }
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":")
                    .append(jsonVector(hashVector(texts.get(i), serverDim))).append('}');
        }
        sb.append("],\"usage\":{\"prompt_tokens\":").append(size).append('}').append('}');
        send(ex, 200, sb.toString(), Map.of());
    }

    private String jsonVector(List<Float> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec.get(i));
        }
        return sb.append(']').toString();
    }

    private void handleChat(HttpExchange ex) throws IOException {
        chatCalls.incrementAndGet();
        JsonNode req = MAPPER.readTree(ex.getRequestBody());
        boolean stream = req.path("stream").asBoolean(false);
        if (!stream) {
            send(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"非流式回答\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4}}",
                    Map.of());
            return;
        }

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            String[] deltas = {"你好", "，", "世界"};
            for (String d : deltas) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + d + "\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void send(HttpExchange ex, int status, String body, Map<String, String> headers)
            throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------------------------------------------------------------- 工具方法

    private DoubaoProperties props(int batchSize, int dim) {
        DoubaoProperties p = new DoubaoProperties();
        p.setApiKey("test-key");
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
        p.setChatModel("test-chat-model");
        p.setEmbeddingModel("doubao-embedding-text-240715");
        p.setEmbeddingDim(dim);
        p.setBatchSize(batchSize);
        p.setMaxRetries(3);
        p.setConnectTimeoutSeconds(2);
        p.setEmbeddingTimeoutSeconds(5);
        p.setChatTimeoutSeconds(5);
        p.setStreamTimeoutSeconds(10);
        p.setRetryBaseMs(50);
        p.setRetryMaxMs(200);
        p.setMaxInputChars(6000);
        p.setEmbeddingMode("auto");
        p.setEmbeddingSequentialFallback(true);
        return p;
    }

    private List<Integer> batchSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (JsonNode req : embedRequests) {
            sizes.add(req.path("input").size());
        }
        return sizes;
    }

    /** 与服务端一致的确定性向量：同一文本 → 同一向量 */
    private List<Float> hashVector(String text, int dim) {
        List<Float> vec = new ArrayList<>(dim);
        long seed = text.hashCode();
        for (int i = 0; i < dim; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            vec.add((float) (((seed >>> 11) % 2000) / 1000.0 - 1.0));
        }
        return vec;
    }
}
