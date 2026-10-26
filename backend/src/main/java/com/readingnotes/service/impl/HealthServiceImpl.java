package com.readingnotes.service.impl;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.ChromaProperties;
import com.readingnotes.service.HealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查服务实现（接口 12）
 */
@Slf4j
@Service
public class HealthServiceImpl implements HealthService {

    private final JdbcTemplate jdbc;
    private final ChromaClient chroma;
    private final DoubaoClient doubao;
    private final ChromaProperties chromaProps;
    private final AppProperties appProps;

    @Value("${app.version:1.0.0}")
    private String appVersion;

    /** 嵌入向量维度（来自 Doubao 模型，用于 chroma embedding_dim_matched 校验） */
    @Value("${doubao.embedding-dim:1024}")
    private int embeddingDim;

    public HealthServiceImpl(JdbcTemplate jdbc, ChromaClient chroma, DoubaoClient doubao,
                             ChromaProperties chromaProps, AppProperties appProps) {
        this.jdbc = jdbc;
        this.chroma = chroma;
        this.doubao = doubao;
        this.chromaProps = chromaProps;
        this.appProps = appProps;
    }

    @Override
    public Map<String, Object> overallHealth() {
        Map<String, Object> data = new HashMap<>();

        // ① 各组件状态
        Map<String, Object> components = new HashMap<>();
        components.put("sqlite", checkSqlite());
        Map<String, Object> chromaComp = checkChroma();
        components.put("chroma", chromaComp);
        components.put("doubao_api", checkDoubao());
        components.put("disk", checkDisk());
        data.put("components", components);

        // ② Chroma 是 RAG 核心依赖，挂掉直接返回 503 + 70001（对齐 API 文档 12.1 Response 503）
        if (!"up".equals(chromaComp.get("status"))) {
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }

        // ③ 整体 status：除 chroma 外的组件 down 则 degraded（不阻断，仍返回 200）
        boolean allUp = components.values().stream()
                .map(c -> ((Map<String, Object>) c).get("status"))
                .allMatch("up"::equals);
        data.put("status", allUp ? "healthy" : "degraded");

        // ④ 版本 + 运行时长
        data.put("version", appVersion);
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        data.put("uptime_seconds", uptimeMs / 1000);

        // ⑤ 活跃任务
        Map<String, Object> activeTasks = new HashMap<>();
        activeTasks.put("building", countBuildingBooks());
        activeTasks.put("ai_running", countRunningEvals());
        data.put("active_tasks", activeTasks);

        return data;
    }

    @Override
    public Map<String, Object> chromaHealth() {
        Map<String, Object> data = new HashMap<>();
        boolean up = chroma.isHealthy();
        data.put("status", up ? "up" : "down");
        String baseUrl = chromaProps.getBaseUrl();
        // 去掉协议前缀，输出 "host:port" 形式（如 127.0.0.1:8000）
        String host = baseUrl == null ? "" : baseUrl.replaceFirst("^https?://", "").replaceAll("/$", "");
        data.put("host", host);

        List<Map<String, Object>> collections = new ArrayList<>();
        boolean dimMatched = true;
        if (up) {
            List<ChromaClient.CollectionInfo> infos = chroma.listCollections();
            for (ChromaClient.CollectionInfo ci : infos) {
                Map<String, Object> col = new HashMap<>();
                col.put("name", ci.name);
                col.put("count", Math.max(0, chroma.count(ci.name)));
                col.put("dimension", ci.dimension);
                collections.add(col);
                // 有任一集合维度与 embedding 模型不匹配则 false
                if (ci.dimension > 0 && ci.dimension != embeddingDim) {
                    dimMatched = false;
                }
            }
        }
        data.put("collections", collections);
        data.put("embedding_dim_matched", dimMatched && up);
        return data;
    }

    // ====================================================================
    // 组件探测
    // ====================================================================

    private Map<String, Object> checkSqlite() {
        Map<String, Object> m = new HashMap<>();
        try {
            // 项目实际用 MySQL（API 文档字段名保留 sqlite），用 SELECT 1 做通用连通性检测
            jdbc.queryForObject("SELECT 1", Integer.class);
            m.put("status", "up");
            // MySQL 无 WAL 模式概念，返回 n/a；若底层是 SQLite 可扩展 PRAGMA journal_mode
            m.put("wal_mode", "n/a");
        } catch (Exception e) {
            m.put("status", "down");
            m.put("wal_mode", "n/a");
            m.put("error", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> checkChroma() {
        Map<String, Object> m = new HashMap<>();
        boolean up = chroma.isHealthy();
        m.put("status", up ? "up" : "down");
        if (up) {
            m.put("collections", chroma.listCollections().size());
        }
        return m;
    }

    private Map<String, Object> checkDoubao() {
        Map<String, Object> m = new HashMap<>();
        int latency = doubao.healthCheck();
        m.put("status", latency >= 0 ? "up" : "down");
        if (latency >= 0) {
            m.put("latency_ms", latency);
        }
        return m;
    }

    private Map<String, Object> checkDisk() {
        Map<String, Object> m = new HashMap<>();
        try {
            // 用配置的数据目录所在磁盘做剩余空间检测，比 user.dir 更准确
            String dataDirPath = appProps.getDataDir();
            File dataDir = new File(dataDirPath != null ? dataDirPath : ".");
            if (!dataDir.exists()) {
                dataDir = dataDir.getAbsoluteFile();
            }
            long freeBytes = dataDir.getUsableSpace();
            double freeGb = Math.round(freeBytes / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0;
            m.put("status", freeGb > 1 ? "up" : "down");
            m.put("free_gb", freeGb);
        } catch (Exception e) {
            m.put("status", "unknown");
            m.put("free_gb", 0);
        }
        return m;
    }

    private int countBuildingBooks() {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM books WHERE status IN ('BUILDING','REBUILDING')",
                    Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private int countRunningEvals() {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eval_results WHERE finished_at IS NULL",
                    Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
