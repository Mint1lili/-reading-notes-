package com.readingnotes.controller;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.common.Result;
import com.readingnotes.config.DoubaoProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查与运维（API 文档 §12）
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ChromaClient chroma;
    private final DoubaoProperties doubaoProps;

    public HealthController(ChromaClient chroma, DoubaoProperties doubaoProps) {
        this.chroma = chroma;
        this.doubaoProps = doubaoProps;
    }

    /** 整体健康 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("chroma", chroma.isHealthy() ? "UP" : "DOWN");
        data.put("doubao_key_configured", doubaoProps.getApiKey() != null && !doubaoProps.getApiKey().isBlank());
        return Result.ok(data);
    }

    /** Chroma 状态 */
    @GetMapping("/health/chroma")
    public Result<Map<String, Object>> chromaHealth() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", chroma.isHealthy() ? "UP" : "DOWN");
        return Result.ok(data);
    }
}
