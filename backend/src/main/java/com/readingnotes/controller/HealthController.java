package com.readingnotes.controller;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.common.Result;
import com.readingnotes.config.DoubaoProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查与运维（API 文档 §12）。
 * <p>
 * {@code /health} 是给负载均衡/前端用的轻量探针，只读缓存不发外部请求；
 * {@code /health/doubao} 会真实调用一次 Embedding（产生费用），因此带 60s 缓存。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /** 探针结果缓存时长：真实调用会计费，避免被轮询打爆 */
    private static final long PROBE_CACHE_MS = 60_000L;

    private final ChromaClient chroma;
    private final DoubaoProperties doubaoProps;
    private final DoubaoClient doubao;

    /** 最近一次豆包探针结果与时间戳；volatile 保证多线程下读到完整对象 */
    private volatile Map<String, Object> probeCache;
    private volatile long probeAt;

    public HealthController(ChromaClient chroma, DoubaoProperties doubaoProps, DoubaoClient doubao) {
        this.chroma = chroma;
        this.doubaoProps = doubaoProps;
        this.doubao = doubao;
    }

    /**
     * 整体健康：进程存活即 UP，依赖状态只作为附加字段，不改变 HTTP 码。
     * <p>
     * 这样 Chroma 挂掉时前端仍能拿到页面，只是构建入口提示 70001，而不是整个站点 503。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("chroma", chroma.isHealthy() ? "UP" : "DOWN");
        data.put("doubao_key_configured", doubaoProps.getApiKey() != null && !doubaoProps.getApiKey().isBlank());
        data.put("doubao_embedding_model", doubaoProps.getEmbeddingModel());
        data.put("doubao_embedding_dim", doubaoProps.getEmbeddingDim());
        return Result.ok(data);
    }

    /** Chroma 状态（读客户端缓存的健康标记，不发请求） */
    @GetMapping("/health/chroma")
    public Result<Map<String, Object>> chromaHealth() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", chroma.isHealthy() ? "UP" : "DOWN");
        return Result.ok(data);
    }

    /**
     * 豆包连通性探针：真实调用一次 /embeddings（文本 "ping"），
     * 校验 key、base-url、模型名与返回维度是否符合 embedding-dim。
     *
     * @param refresh true 时跳过缓存强制重探（配置改完立刻验证用）
     * @return status / dim / latency_ms 等诊断字段，另附 cached 与 age_ms
     */
    @GetMapping("/health/doubao")
    public Result<Map<String, Object>> doubaoHealth(
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && probeCache != null && now - probeAt < PROBE_CACHE_MS) {
            Map<String, Object> cached = new HashMap<>(probeCache);
            cached.put("cached", true);
            cached.put("age_ms", now - probeAt);
            return Result.ok(cached);
        }
        Map<String, Object> fresh = doubao.probe();
        fresh.put("cached", false);
        probeCache = fresh;
        probeAt = now;
        return Result.ok(fresh);
    }
}
