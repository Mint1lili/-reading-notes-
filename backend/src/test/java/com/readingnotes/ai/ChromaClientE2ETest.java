package com.readingnotes.ai;

import com.readingnotes.config.ChromaProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Chroma 客户端端到端测试（需要本地 Chroma Server 处于运行状态）。
 * <p>
 * Chroma 未启动时通过 {@code assumeTrue} 跳过，不阻塞离线 CI。
 * 断言逻辑与离线手跑入口 {@link ChromaLifecycleCheck} 共用，避免两份实现漂移。
 */
class ChromaClientE2ETest {

    private static ChromaClient client;

    @BeforeAll
    static void setUp() {
        ChromaProperties props = new ChromaProperties();
        props.setBaseUrl(System.getProperty("chroma.url", "http://localhost:8000"));
        props.setApiVersion(System.getProperty("chroma.api", "v2"));
        client = new ChromaClient(props);
    }

    @Test
    void fullLifecycle() {
        assumeTrue(client.isHealthy(), "Chroma 未启动，跳过 E2E");

        String col = "book_e2e_" + System.currentTimeMillis();
        try {
            assertDoesNotThrow(() -> ChromaLifecycleCheck.run(client, col),
                    "Chroma 生命周期自检失败");
        } finally {
            // 删除集合与重复删除必须幂等（Chroma 对不存在的集合返回 404）
            client.deleteCollection(col);
            client.deleteCollection(col);
        }
    }
}
