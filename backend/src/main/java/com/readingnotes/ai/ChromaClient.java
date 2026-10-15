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
 */
@Slf4j
@Component
public class ChromaClient {

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

    /** 健康检查：GET /api/v1/heartbeat */
    public boolean isHealthy() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(props.getBaseUrl() + "/api/v1/heartbeat"))
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

    /** 创建集合（维度校验，需求 F2.4） */
    public void createCollection(String name, int dim) {
        ensureHealthy();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            ObjectNode meta = mapper.createObjectNode();
            meta.put("hnsw:space", "cosine");
            meta.put("dimension", dim);
            body.set("metadata", meta);
            HttpRequest req = post("/api/v1/collections", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // 已存在时返回 409 属正常，忽略
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
            HttpRequest req = post("/api/v1/collections/" + encode(collection) + "/upsert", body);
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
            body.set("query_embeddings", mapper.createObjectNode().set("0", mapper.valueToTree(queryEmbedding)));
            body.put("n_results", nResults);
            body.put("include", mapper.valueToTree(List.of("documents", "metadatas", "distances")));
            if (where != null && !where.isEmpty()) {
                body.set("where", mapper.valueToTree(where));
            }
            HttpRequest req = post("/api/v1/collections/" + encode(collection) + "/query", body);
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
            ObjectNode body = mapper.createObjectNode();
            body.set("where", mapper.valueToTree(where));
            HttpRequest req = post("/api/v1/collections/" + encode(collection) + "/delete", body);
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

    /** 删除整个集合（F1.2 删除书籍、重建知识库时） */
    public void deleteCollection(String name) {
        ensureHealthy();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/api/v1/collections/" + encode(name)))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 404) {
                log.error("delete collection {} status={} body={}", name, resp.statusCode(), resp.body());
                throw new BusinessException(ErrorCode.CHROMA_DOWN);
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
