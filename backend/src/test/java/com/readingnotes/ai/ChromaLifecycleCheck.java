package com.readingnotes.ai;

import com.readingnotes.config.ChromaProperties;

import java.util.List;
import java.util.Map;

/**
 * Chroma 客户端生命周期自检。
 * <p>
 * 刻意不依赖 JUnit：离线环境（mvn 仓库缺少 surefire 插件）下可用 javac/java 直接运行，
 * 同时在 CI 里由 {@link ChromaClientE2ETest} 复用，保证两条路径验证的是同一份逻辑。
 * <p>
 * 覆盖链路：建集合（幂等）→ upsert（同 id 覆盖，验证断点续传的幂等前提）→ query（含 where 过滤）
 * → delete-by-where → 删集合（幂等）。
 */
public final class ChromaLifecycleCheck {

    private ChromaLifecycleCheck() {
    }

    /**
     * 对指定集合跑一轮完整生命周期，任一步断言失败即抛异常。
     *
     * @param client    已构造好的客户端
     * @param col       集合名（方法内负责创建与最终不删除，删除由调用方在 finally 处理）
     */
    public static void run(ChromaClient client, String col) {
        // 1) 创建集合；重复创建必须幂等返回，不能抛 409
        client.createCollection(col, 3);
        client.createCollection(col, 3);

        List<String> ids = List.of("c1", "c2");
        List<List<Float>> vecs = List.of(
                List.of(1.0f, 0.0f, 0.0f),
                List.of(0.0f, 1.0f, 0.0f));
        List<String> docs = List.of("原文A", "原文B");
        List<Map<String, Object>> metas = List.of(
                Map.of("book_id", "bkE2E", "page", 1),
                Map.of("book_id", "bkE2E", "page", 2));

        // 2) 写入；再用相同 id 覆盖写入一次，模拟断点续传重跑同一批（依赖 chunk_id 确定性）
        client.upsert(col, ids, vecs, docs, metas);
        client.upsert(col, ids, vecs, docs, metas);

        // 3) 检索：覆盖写入后仍应是 2 条（而非 4 条），且 c1 更相似
        List<ChromaClient.SearchHit> hits =
                client.query(col, List.of(1.0f, 0.0f, 0.0f), Map.of("book_id", "bkE2E"), 5);
        check(hits.size() == 2, "upsert 未幂等，命中 " + hits.size() + " 条，期望 2 条");
        check("c1".equals(hits.get(0).getId()), "最相似的应是 c1，实际 " + hits.get(0).getId());
        check(hits.get(0).getDistance() < hits.get(1).getDistance(), "c1 的距离应小于 c2");
        check("原文A".equals(hits.get(0).getDocument()), "document 未正确回传");
        check(hits.get(0).getMetadata().path("page").asInt() == 1, "metadata 未正确回传");

        // 4) where 过滤：不存在的 book_id 应返回 0 条（F5 章节过滤的前提）
        check(client.query(col, List.of(1.0f, 0.0f, 0.0f), Map.of("book_id", "NOPE"), 5).isEmpty(),
                "where 过滤未生效");

        // 5) 按 where 删除（删除书籍/笔记的清理路径）
        client.deleteByWhere(col, Map.of("book_id", "bkE2E"));
        check(client.query(col, List.of(1.0f, 0.0f, 0.0f), Map.of("book_id", "bkE2E"), 5).isEmpty(),
                "delete-by-where 后仍能查到向量");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException("ASSERT FAILED: " + msg);
        }
    }

    /**
     * 手动入口：java ChromaLifecycleCheck [baseUrl] [apiVersion]
     */
    public static void main(String[] args) {
        ChromaProperties props = new ChromaProperties();
        props.setBaseUrl(args.length > 0 ? args[0] : "http://localhost:8000");
        props.setApiVersion(args.length > 1 ? args[1] : "v2");
        ChromaClient client = new ChromaClient(props);

        if (!client.isHealthy()) {
            System.out.println("SKIP: chroma not running at " + props.getBaseUrl());
            return;
        }
        String col = "book_e2e_" + System.currentTimeMillis();
        try {
            run(client, col);
            System.out.println("ALL_PASS");
        } finally {
            // 删除集合与重复删除都必须幂等（Chroma 对不存在的集合返回 404）
            try {
                client.deleteCollection(col);
                client.deleteCollection(col);
            } catch (Exception e) {
                System.out.println("WARN cleanup failed: " + e);
            }
        }
    }
}
