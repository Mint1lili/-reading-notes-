package com.readingnotes.ai;

import com.readingnotes.config.DoubaoProperties;

import java.util.List;

/**
 * 独立验证入口：不依赖 Spring / MySQL，直接验证大模型客户端
 * 运行前在 IDEA Run Configuration 设置 4 个环境变量：
 *   DOUBAO_API_KEY=你的key
 *   DOUBAO_BASE_URL=https://aigw-gzgy2.cucloud.cn:8443/v1
 *   DOUBAO_CHAT_MODEL=Qwen3.6-35B-A3B
 *   DOUBAO_EMBEDDING_MODEL=Qwen-Embedding
 */
public class DoubaoQuickTest {

    public static void main(String[] args) {
        DoubaoProperties props = new DoubaoProperties();
        props.setApiKey(System.getenv("DOUBAO_API_KEY"));
        props.setBaseUrl(System.getenv("DOUBAO_BASE_URL"));
        props.setChatModel(System.getenv("DOUBAO_CHAT_MODEL"));
        props.setEmbeddingModel(System.getenv("DOUBAO_EMBEDDING_MODEL"));
        props.setEmbeddingDim(1024);
        props.setBatchSize(16);
        props.setMaxRetries(3);

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            System.out.println("❌ 未设置 DOUBAO_API_KEY，请在 IDEA Run Configuration 的环境变量里配置后再运行");
            return;
        }
        System.out.println("base-url : " + props.getBaseUrl());
        System.out.println("chat-model: " + props.getChatModel());
        System.out.println("embedding : " + props.getEmbeddingModel());

        DoubaoClient client = new DoubaoClient(props);

        // 1. 非流式 chat
        System.out.println("========== 1. 非流式 chat ==========");
        try {
            String answer = client.chat(List.of(
                    new DoubaoClient.ChatMessage("system", "你是简短的助手"),
                    new DoubaoClient.ChatMessage("user", "用一句话介绍你自己")
            ), 0.3, 100);
            System.out.println("回答：" + answer);
            System.out.println("✅ chat 成功");
        } catch (Exception e) {
            System.out.println("❌ chat 失败：" + e.getMessage());
        }

        // 2. 流式 chat
        System.out.println("========== 2. 流式 chat ==========");
        try {
            client.chatStream(List.of(
                    new DoubaoClient.ChatMessage("user", "数到5，用逗号分隔")
            ), 0.3, 100, delta -> System.out.print(delta));
            System.out.println();
            System.out.println("✅ 流式 chat 成功");
        } catch (Exception e) {
            System.out.println("❌ 流式失败：" + e.getMessage());
        }

        // 3. Embedding（智谱 Embedding-3，独立于豆包/联通云）
        System.out.println("========== 3. Embedding（智谱） ==========");
        String zhipuKey = System.getenv("AI_EMBEDDING_API_KEY");
        if (zhipuKey == null || zhipuKey.isBlank()) {
            System.out.println("❌ 未设置 AI_EMBEDDING_API_KEY，请在 Run Configuration 环境变量里配置后再运行");
            return;
        }
        try {
            ApiEmbeddingProvider provider = new ApiEmbeddingProvider(
                    System.getenv().getOrDefault("AI_EMBEDDING_API_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"),
                    zhipuKey,
                    System.getenv().getOrDefault("AI_EMBEDDING_API_MODEL", "embedding-3"),
                    Integer.parseInt(System.getenv().getOrDefault("AI_EMBEDDING_API_DIM", "2048")),
                    System.getenv("AI_EMBEDDING_INSTRUCTIONS"));
            System.out.println("embedding model: " + System.getenv().getOrDefault("AI_EMBEDDING_API_MODEL", "embedding-3"));
            List<List<Float>> vectors = provider.embed(List.of("测试向量", "今天天气很好"));
            System.out.println("✅ 向量维度：" + vectors.get(0).size() + "，条数：" + vectors.size());
        } catch (Exception e) {
            System.out.println("❌ embedding 失败：" + e.getMessage());
        }
    }
}
