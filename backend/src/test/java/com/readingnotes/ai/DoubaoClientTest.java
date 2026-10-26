package com.readingnotes.ai;

import com.readingnotes.ai.DoubaoClient.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DoubaoClient 验证测试
 * 运行前必须先设置环境变量 DOUBAO_API_KEY
 * Windows PowerShell:  $env:DOUBAO_API_KEY="你的key"
 * IDEA: Run Configuration → Environment variables 里加
 */
@SpringBootTest
class DoubaoClientTest {

    @Autowired
    private DoubaoClient doubaoClient;

    /**
     * 测试1：非流式对话
     * 预期：控制台打印一段完整回答
     */
    @Test
    void testChat() {
        System.out.println("========== 测试1：非流式 chat ==========");
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "你是一个简短的助手，回答控制在50字以内"),
                new ChatMessage("user", "用一句话解释什么是RAG")
        );
        String answer = doubaoClient.chat(messages, 0.3, 256);
        System.out.println("豆包回答：" + answer);
        assertNotNull(answer);
        assertFalse(answer.isBlank());
        System.out.println("✅ 非流式 chat 正常\n");
    }

    /**
     * 测试2：流式对话（SSE）
     * 预期：控制台一个字一个字打印出来
     */
    @Test
    void testChatStream() {
        System.out.println("========== 测试2：流式 chatStream ==========");
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "你是一个简短的助手"),
                new ChatMessage("user", "数到5，每个数字之间用逗号隔开")
        );
        StringBuilder sb = new StringBuilder();
        doubaoClient.chatStream(messages, 0.3, 256, delta -> {
            System.out.print(delta);
            sb.append(delta);
        });
        System.out.println(); // 换行
        System.out.println("累计输出：" + sb);
        assertFalse(sb.isEmpty());
        System.out.println("✅ 流式 chatStream 正常\n");
    }

    /**
     * 测试3：Embedding 向量化
     * 预期：返回3个向量，每个向量1024维
     */
    @Test
    void testEmbed() {
        System.out.println("========== 测试3：Embedding 向量化 ==========");
        List<String> texts = List.of(
                "习惯回路包括暗示、惯常行为和奖赏",
                "RAG是检索增强生成技术",
                "今天天气真好"
        );
        List<List<Float>> vectors = doubaoClient.embed(texts);
        System.out.println("返回向量数量：" + vectors.size());
        assertEquals(3, vectors.size(), "应该返回3个向量");
        int dim = vectors.get(0).size();
        System.out.println("第一个向量维度：" + dim + "（不同模型维度不同，>0即可）");
        assertTrue(dim > 0, "向量维度必须大于0");

        // 打印第一个向量的前5个数字
        System.out.print("第一个向量前5个数字：");
        for (int i = 0; i < Math.min(5, dim); i++) {
            System.out.printf("%.4f ", vectors.get(0).get(i));
        }
        System.out.println();
        System.out.println("✅ Embedding 正常\n");
    }
}
