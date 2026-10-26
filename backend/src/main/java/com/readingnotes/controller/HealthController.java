package com.readingnotes.controller;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import com.readingnotes.service.HealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查与运维接口（API 文档 §12）
 * 12.1 GET /health        整体健康（SQLite/Chroma/豆包/磁盘 + 活跃任务）
 * 12.2 GET /health/chroma Chroma 状态（集合列表 + 维度校验）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * 12.1 整体健康检查。
     * <p>
     * Chroma 挂掉时返回 503 + 70001，data 里只带 down 的组件（对齐 API 文档示例）；
     * 其他组件异常不阻断，仍返回 200 + status=degraded。
     */
    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> overall() {
        try {
            Map<String, Object> data = healthService.overallHealth();
            return ResponseEntity.ok(Result.ok(data));
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.CHROMA_DOWN.getCode()) {
                Map<String, Object> chromaDown = new HashMap<>();
                chromaDown.put("status", "down");
                Map<String, Object> components = new HashMap<>();
                components.put("chroma", chromaDown);
                Map<String, Object> data = new HashMap<>();
                data.put("components", components);
                Result<Map<String, Object>> r = Result.fail(e.getCode(), e.getMessage());
                r.setData(data);
                return ResponseEntity.status(e.getHttpStatus()).body(r);
            }
            throw e;
        }
    }

    /** 12.2 Chroma 状态 — 未启动抛 70001，维度不匹配抛 70002 */
    @GetMapping("/chroma")
    public Result<Map<String, Object>> chroma() {
        Map<String, Object> data = healthService.chromaHealth();
        String status = (String) data.get("status");
        if (!"up".equals(status)) {
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
        Boolean dimMatched = (Boolean) data.get("embedding_dim_matched");
        if (Boolean.FALSE.equals(dimMatched)) {
            throw new BusinessException(ErrorCode.DIM_MISMATCH);
        }
        return Result.ok(data);
    }
}
