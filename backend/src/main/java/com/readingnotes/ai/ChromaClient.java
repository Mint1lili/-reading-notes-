package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.ChromaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Chroma 向量库客户端（Server 模式，chroma run --port 8000）
 * 覆盖脚手架所需的最小操作集：
 * - 集合管理：get/create/delete
 * - 写入：upsert（断点续传用，覆盖旧向量）
 * - 查询：query（含 where 元数据过滤，cosine）
 * - 运维：delete-by-where、snapshot（F5.5 备份）
 * 集合分布（数据库设计 §6）：book_{book_id} 原书切片；notes_all 笔记回流
 *
 * <p>协议层适配 Chroma 1.x（1.5.9 实测）：REST 走 /api/v2/tenants/default_tenant/databases/default_database/
 * collections/{collection_id}（1.x 移除旧 /api/v1，410 Gone；集合按 uuid 寻址，先按 name 解析 id）。
 * 1.5.9 的集合维度由首次 upsert 自动确定，createCollection 不再传 hnsw.dimension；
 * 1.5.9 实测不支持 DELETE 集合（404/405），deleteCollection 幂等容忍并告警（F1.2 使用方知悉）。
 */
@Slf4j
@Component
public class ChromaClient {

    /** Chroma 1.x 多租户默认命名空间（chroma run 本地默认值，OpenAPI 实测） */
    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";

    private final ChromaProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ChromaClient(ChromaProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }

    /** 健康检查：GET /api/v2/heartbeat */
    public boolean isHealthy() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(props.getBaseUrl() + "/api/v2/heartbeat"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void ensureHealthy() {
        if (!isHealthy()) {
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /**
     * 创建集合（维度校验，需求 F2.4）。
     * <p>Chroma 1.x 的维度由首次 upsert 自动确定（hnsw 配置不接受 dimension 字段），
     * dim 参数保留签名以兼容调用方，实际不参与请求体。
     */
    public void createCollection(String name, int dim) {
        ensureHealthy();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            body.put("get_or_create", true);
            ObjectNode config = mapper.createObjectNode();
            ObjectNode hnsw = mapper.createObjectNode();
            hnsw.put("space", "cosine");
            config.set("hnsw", hnsw);
            body.set("configuration", config);
            HttpRequest req = post("/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // get_or_create 幂等；兼容旧版本 409 容忍
            if (resp.statusCode() != 200 && resp.statusCode() != 409) {
                log.error("create collection {} status={} body={}", name, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("create collection failed name={}", name, e);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /**
     * 批量写入向量（upsert）
     * ids: 每条记录的 id（业务上 = chunk_id，保证断点续传不重复）
     * embeddings: 向量列表（与 ids 对齐）
     * documents: 原文（便于回溯）
     * metadatas: 元数据（book_id/page/chapter_path/chunk_id 等）
     * 集合不存在时自动创建（1.x 语义：首次 upsert 同时定维）。
     */
    public void upsert(String collection, List<String> ids,
                       List<List<Float>> embeddings,
                       List<String> documents,
                       List<Map<String, Object>> metadatas) {
        ensureHealthy();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("ids", mapper.valueToTree(ids));
            body.set("embeddings", mapper.valueToTree(embeddings));
            body.set("documents", mapper.valueToTree(documents));
            body.set("metadatas", mapper.valueToTree(metadatas));
            HttpRequest req = post(collectionPath(collection, true) + "/upsert", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("upsert failed collection={} status={} body={}", collection, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("upsert failed collection={}", collection, e);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /**
     * 向量检索（cosine）
     * queryEmbedding: 问题向量
     * where: 元数据过滤（如 chapter_path 前缀，需求 4.2 ②）
     * nResults: Top-K
     * 返回：按相似度降序的 {id, document, metadata, distance}
     */
    public List<SearchHit> query(String collection, List<Float> queryEmbedding,
                                 Map<String, Object> where, int nResults) {
        ensureHealthy();
        try {
            ObjectNode body = mapper.createObjectNode();
            // 1.x v2：query_embeddings 为正规二维数组（旧 v1 的 {"0": [...]} 包装已移除）
            ArrayNode qes = mapper.createArrayNode();
            qes.add(mapper.valueToTree(queryEmbedding));
            body.set("query_embeddings", qes);
            body.put("n_results", nResults);
            body.put("include", mapper.valueToTree(List.of("documents", "metadatas", "distances")));
            if (where != null && !where.isEmpty()) {
                body.set("where", mapper.valueToTree(where));
            }
            String path = collectionPath(collection, false);
            if (path == null) {
                return List.of(); // 集合不存在 → 视为空知识库，返回空命中（不自动建集合）
            }
            HttpRequest req = post(path + "/query", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("query failed collection={} status={} body={}", collection, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
            }
            JsonNode root = mapper.readTree(resp.body());
            return parseHits(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("query failed collection={}", collection, e);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /** 按 where 条件删除向量（如删除书籍/笔记的全部向量） */
    public void deleteByWhere(String collection, Map<String, Object> where) {
        ensureHealthy();
        try {
            String path = collectionPath(collection, false);
            if (path == null) {
                return; // 集合不存在 → 无向量可清，幂等 no-op
            }
            ObjectNode body = mapper.createObjectNode();
            body.set("where", mapper.valueToTree(where));
            HttpRequest req = post(path + "/delete", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("delete failed collection={} status={} body={}", collection, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("delete failed collection={}", collection, e);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /**
     * 删除整个集合（F1.2 删除书籍、重建知识库时）。
     * <p>Chroma 1.5.9 实测 DELETE 集合端点不可用（404/405），此处幂等容忍：
     * 集合不存在视为已删除；删除失败仅告警，不抛业务异常（F1.2 使用方自行决定是否降级为按条件清空）。
     */
    public void deleteCollection(String name) {
        ensureHealthy();
        try {
            String path = collectionPath(name, false);
            if (path == null) {
                return; // 集合不存在 → 幂等
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + path))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 404 && resp.statusCode() != 405) {
                log.error("delete collection {} status={} body={}", name, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
            }
            if (resp.statusCode() == 405 || (resp.statusCode() == 404 && resp.body().contains("NotFound"))) {
                log.warn("delete collection {} not supported by Chroma {} (status={}), collection may remain. "
                        + "F1.2 如需彻底删除集合，请降级为 deleteByWhere 清空记录。", name, resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("delete collection failed name={}", name, e);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    private List<SearchHit> parseHits(JsonNode root) {
        JsonNode ids = root.path("ids").path(0);
        JsonNode docs = root.path("documents").path(0);
        JsonNode metas = root.path("metadatas").path(0);
        JsonNode dists = root.path("distances").path(0);
        List<SearchHit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            SearchHit h = new SearchHit();
            h.setId(ids.get(i).asText());
            h.setDocument(docs.path(i).asText(""));
            h.setMetadata(metas.path(i));
            h.setDistance(dists.path(i).asDouble());
            hits.add(h);
        }
        return hits;
    }

    /**
     * 集合 uuid 路径（1.x 按 name 先解析 id；缺失时按 createIfMissing 决定自动建或返回 null）。
     * 返回 null 表示集合不存在且不创建。
     */
    private String collectionPath(String collection, boolean createIfMissing) throws Exception {
        String id = resolveCollectionId(collection);
        if (id != null) {
            return "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections/" + id;
        }
        if (createIfMissing) {
            createCollection(collection, 0);
            id = resolveCollectionId(collection);
            if (id != null) {
                return "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections/" + id;
            }
            throw new BusinessException(ErrorCode.CHROMA_DOWN, "Chroma 集合创建后仍不可寻址: " + collection);
        }
        return null;
    }

    /** 按名称查集合 uuid（GET collections?name=，1.x 返回数组取首个） */
    private String resolveCollectionId(String name) throws Exception {
        String url = props.getBaseUrl() + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                + "/collections?name=" + encode(name);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray() && arr.size() > 0 && !arr.get(0).path("id").isMissingNode()) {
                return arr.get(0).path("id").asText();
            }
        }
        return null;
    }

    private HttpRequest post(String path, JsonNode body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private String encode(String name) {
        return name.replace("/", "%2F");
    }

    /** 检索命中记录 */
    @lombok.Data
    public static class SearchHit {
        private String id;
        private String document;
        private JsonNode metadata;
        private double distance;
    }
}
