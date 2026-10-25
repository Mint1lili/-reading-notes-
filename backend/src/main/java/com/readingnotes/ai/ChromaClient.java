package com.readingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.ChromaProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma 向量库客户端（Server 模式，chroma run --port 8000）
 * 覆盖脚手架所需的最小操作集：
 * - 集合管理：get/create/delete
 * - 写入：upsert（断点续传用，覆盖旧向量）
 * - 查询：query（含 where 元数据过滤，cosine）
 * - 运维：delete-by-where、snapshot（F5.5 备份）
 * 集合分布（数据库设计 §6）：book_{book_id} 原书切片；notes_all 笔记回流
 * <p>
 * <b>API 版本兼容</b>：chromadb 1.x 起服务端只实现 v2，v1 路径统一返回 410，
 * 因此本客户端默认走 v2，并通过 {@code chroma.api-version} 保留回退到 v1 的能力。
 * 两种模式仅在「路径」与「query_embeddings 的序列化形状」上有差异：
 * <pre>
 *   v2: /api/v2/tenants/{tenant}/databases/{database}/collections/{uuid}/upsert
 *       query_embeddings = [[0.1, 0.2, ...]]        （二维数组）
 *   v1: /api/v1/collections/{name}/upsert
 *       query_embeddings = {"0": [0.1, 0.2, ...]}    （字典）
 * </pre>
 * 响应体两种模式一致，均为按查询分组的嵌套数组（ids[0]、documents[0]、distances[0]）。
 */
@Slf4j
@Component
public class ChromaClient {

    private final ChromaProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /** true = chromadb 1.x 的 /api/v2 布局；false = 旧版 /api/v1 布局 */
    private final boolean v2;

    /** 集合根路径，形如 /api/v2/tenants/default_tenant/databases/default_database/collections 或 /api/v1/collections */
    private final String collectionsPath;

    /**
     * v2 模式下集合按 UUID 寻址（传名称会 400 Collection ID is not a valid UUIDv4），
     * 这里缓存 name -> collection_id，避免每次 upsert/query 前多一次解析请求。
     * 集合被删除或外部重建后 UUID 会变，故在收到 404 时失效并重新解析。
     */
    private final Map<String, String> collectionIds = new ConcurrentHashMap<>();

    public ChromaClient(ChromaProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
        this.v2 = !"v1".equalsIgnoreCase(props.getApiVersion());
        this.collectionsPath = v2
                ? "/api/v2/tenants/" + enc(props.getTenant(), "default_tenant")
                        + "/databases/" + enc(props.getDatabase(), "default_database") + "/collections"
                : "/api/v1/collections";
    }

    /** 健康检查：v2 = GET /api/v2/heartbeat，v1 = GET /api/v1/heartbeat */
    public boolean isHealthy() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(props.getBaseUrl() + (v2 ? "/api/v2/heartbeat" : "/api/v1/heartbeat")))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 快速失败：调用任何写操作前先探活，避免把连接异常包装成难以定位的 500 */
    public void ensureHealthy() {
        if (!isHealthy()) {
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
    }

    /** 创建集合（维度校验，需求 F2.4）；已存在时幂等返回 */
    public void createCollection(String name, int dim) {
        ensureHealthy();
        try {
            // 先清掉本地缓存再解析：集合可能在向量库重启/清理/换实例后消失，
            // 而缓存里还留着旧 UUID。若沿用缓存会直接 return，把一个"不存在的集合"
            // 当成已就绪，随后 upsert 一路 404 —— 续跑重试永远补不齐。
            // 该方法只在构建开始时调用一次，多一次 GET 的代价可以忽略。
            collectionIds.remove(name);
            if (resolveCollectionId(name) != null) {
                return;
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            ObjectNode meta = mapper.createObjectNode();
            meta.put("hnsw:space", "cosine");
            meta.put("dimension", dim);
            body.set("metadata", meta);
            if (v2) {
                // 并发下两个请求同时判定“不存在”时，get_or_create 可避免一方抛 409
                body.put("get_or_create", true);
            }
            HttpRequest req = post(collectionsPath, body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode id = mapper.readTree(resp.body()).path("id");
                if (!id.isMissingNode() && !id.asText().isEmpty()) {
                    collectionIds.put(name, id.asText());
                }
                return;
            }
            // 已存在时返回 409 属正常：清掉缓存让后续请求重新解析出真实 UUID
            if (resp.statusCode() == 409) {
                collectionIds.remove(name);
                return;
            }
            log.error("create collection {} status={} body={}", name, resp.statusCode(), resp.body());
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
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
            String cid = resolveOrThrow(collection);
            HttpRequest req = post(collectionsPath + "/" + cid + "/upsert", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // 集合被外部删除后重建，缓存的 UUID 会失效：失效重解析后重试一次
            if (resp.statusCode() == 404) {
                collectionIds.remove(collection);
                cid = resolveCollectionId(collection);
                if (cid == null) {
                    // 集合在构建过程中被删掉（向量库重启/人工清理）：按本批向量维度重建后重试，
                    // 否则整本书只能停在 CHROMA_DOWN，重试也没有意义。
                    int dim = embeddings.isEmpty() || embeddings.get(0) == null
                            ? 0 : embeddings.get(0).size();
                    if (dim > 0) {
                        log.warn("collection {} vanished mid-build, recreating dim={}", collection, dim);
                        createCollection(collection, dim);
                        cid = resolveCollectionId(collection);
                    }
                }
                if (cid == null) {
                    throw new BusinessException(ErrorCode.CHROMA_DOWN);
                }
                resp = http.send(post(collectionsPath + "/" + cid + "/upsert", body), HttpResponse.BodyHandlers.ofString());
            }
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
            // v2 用二维数组，v1 用 {"0": [...]} 字典；响应体两者一致，均为嵌套数组
            if (v2) {
                body.set("query_embeddings", mapper.valueToTree(List.of(queryEmbedding)));
            } else {
                body.set("query_embeddings", mapper.createObjectNode().set("0", mapper.valueToTree(queryEmbedding)));
            }
            body.put("n_results", nResults);
            body.put("include", mapper.valueToTree(List.of("documents", "metadatas", "distances")));
            if (where != null && !where.isEmpty()) {
                body.set("where", mapper.valueToTree(where));
            }
            String cid = resolveOrThrow(collection);
            HttpRequest req = post(collectionsPath + "/" + cid + "/query", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                collectionIds.remove(collection);
                cid = resolveOrThrow(collection);
                resp = http.send(post(collectionsPath + "/" + cid + "/query", body), HttpResponse.BodyHandlers.ofString());
            }
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
            String cid = resolveOrThrow(collection);
            HttpRequest req = post(collectionsPath + "/" + cid + "/delete", body);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                // 集合不存在时等价于“无向量可删”，不视为故障（删除笔记/书籍的幂等要求）
                log.debug("delete-by-where skipped, collection {} not found", collection);
                return;
            }
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
     * 删除整个集合（F1.2 删除书籍、重建知识库时）
     * <p>
     * 注意：v2 的 upsert/query 只能按 UUID 寻址，但“删除集合”这一操作仍按名称寻址
     * （实测 DELETE /api/v2/.../collections/{name} 返回 200），因此这里统一传 encode(name)。
     */
    public void deleteCollection(String name) {
        ensureHealthy();
        collectionIds.remove(name);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + collectionsPath + "/" + encode(name)))
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

    /**
     * 集合内向量条数（构建完成后的完整性校验用，需求 F2.4）。
     * <p>
     * v2 与 v1 的 count 端点都支持按集合键寻址；集合不存在时返回 -1 而不是抛异常——
     * 调用方据此区分"库挂了"（异常）与"集合被外部删了"（-1，应视为 0 条）。
     */
    public long count(String collection) {
        ensureHealthy();
        try {
            String cid = resolveCollectionId(collection);
            if (cid == null) {
                return -1L;
            }
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(props.getBaseUrl() + collectionsPath + "/" + cid + "/count"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                collectionIds.remove(collection);
                cid = resolveCollectionId(collection);
                if (cid == null) {
                    return -1L;
                }
                resp = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(props.getBaseUrl() + collectionsPath + "/" + cid + "/count"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
            if (resp.statusCode() != 200) {
                log.warn("count collection {} status={} body={}", collection, resp.statusCode(), resp.body());
                return -1L;
            }
            String body = resp.body() == null ? "" : resp.body().trim().replace("\"", "");
            return body.isEmpty() ? 0L : Long.parseLong(body);
        } catch (NumberFormatException e) {
            log.warn("count response not a number collection={}", collection);
            return -1L;
        } catch (Exception e) {
            log.warn("count collection {} failed: {}", collection, e.toString());
            return -1L;
        }
    }

    /** 解析检索响应：ids/documents/metadatas/distances 均按查询分组，这里只取第 0 组 */
    private List<SearchHit> parseHits(JsonNode root) {
        JsonNode ids = root.path("ids").path(0);
        JsonNode docs = root.path("documents").path(0);
        JsonNode metas = root.path("metadatas").path(0);
        JsonNode dists = root.path("distances").path(0);
        List<SearchHit> hits = new ArrayList<>();
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
     * 解析集合寻址键：v2 返回 UUID，v1 返回 URL 编码后的名称。
     *
     * @return 集合键；集合不存在时返回 {@code null}
     */
    private String resolveCollectionId(String name) {
        if (!v2) {
            return encode(name);
        }
        String cached = collectionIds.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(props.getBaseUrl() + collectionsPath + "/" + encode(name)))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode id = mapper.readTree(resp.body()).path("id");
                if (!id.isMissingNode() && !id.asText().isEmpty()) {
                    collectionIds.put(name, id.asText());
                    return id.asText();
                }
                return null;
            }
            if (resp.statusCode() != 404) {
                log.warn("resolve collection {} status={} body={}", name, resp.statusCode(), resp.body());
            }
            return null;
        } catch (Exception e) {
            log.warn("resolve collection {} failed: {}", name, e.toString());
            return null;
        }
    }

    /** 解析集合键，不存在则抛业务异常（调用方应在 upsert/query 前先 createCollection） */
    private String resolveOrThrow(String name) {
        String cid = resolveCollectionId(name);
        if (cid == null) {
            log.error("collection {} not found, caller must createCollection first", name);
            throw new BusinessException(ErrorCode.CHROMA_DOWN);
        }
        return cid;
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

    /** 路径段安全编码，配置缺失时回退默认值 */
    private static String enc(String seg, String fallback) {
        if (seg == null || seg.isEmpty()) {
            return fallback;
        }
        return seg.replace("/", "%2F");
    }

    /** 检索命中记录：distance 为 cosine 距离（越小越相似），转相似度需 1-distance */
    @Data
    public static class SearchHit {
        /** 向量 id，等于 chunk_id */
        private String id;
        /** 原始切片正文 */
        private String document;
        /** 构建时写入的元数据：book_id / page / chapter_path 等 */
        private JsonNode metadata;
        /** cosine 距离，0 表示完全一致 */
        private double distance;
    }
}
