package com.readingnotes.controller;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.ai.RagPipeline;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书本内 AI 问答接口（需求 F3.2 接线）。
 * <p>
 * 检索由 {@link RagPipeline} 承担（向量召回 + 章节/页码过滤 + 相似度阈值兜底拒答），
 * 本控制器负责：参数校验 → 检索 → 拼接上下文 → 豆包对话生成 → 引用溯源返回。
 * <p>
 * 契约：POST /api/v1/ai/qa  body={"book_id":"...","question":"..."}
 * 成功：{ answer, citations:[{chunk_id,page,chapter_path,similarity,snippet}] }
 * 拒答：code=40002「本书中未提及相关内容」。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final RagPipeline ragPipeline;
    private final DoubaoClient doubaoClient;

    public AiController(RagPipeline ragPipeline, DoubaoClient doubaoClient) {
        this.ragPipeline = ragPipeline;
        this.doubaoClient = doubaoClient;
    }

    @PostMapping("/qa")
    public Result<Map<String, Object>> qa(@RequestBody Map<String, Object> body) {
        String bookId = str(body.get("book_id"));
        String question = str(body.get("question"));
        if (bookId == null || bookId.isBlank() || question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "book_id 与 question 不能为空");
        }

        // ① 向量检索（含章节/页码元数据过滤 + 相似度阈值兜底）
        RagPipeline.RetrieveResult rr = ragPipeline.retrieve("book_" + bookId, question);
        if (rr.isRefused()) {
            throw ragPipeline.notMentioned();
        }

        // ② 拼接上下文并生成引用溯源
        StringBuilder ctx = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        int rank = 0;
        for (ChromaClient.SearchHit hit : rr.getHits()) {
            rank++;
            String snippet = truncate(hit.getDocument(), 220);
            ctx.append("[片段").append(rank).append("] ").append(snippet).append("\n");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("chunk_id", hit.getId());
            c.put("page", hit.getMetadata() != null ? hit.getMetadata().path("page").asInt(0) : 0);
            c.put("chapter_path", hit.getMetadata() != null ? hit.getMetadata().path("chapter_path").asText("") : "");
            c.put("similarity", round(1.0 - hit.getDistance()));
            c.put("snippet", snippet);
            citations.add(c);
        }

        // ③ 对话生成
        String system = "你是「智读」AI 阅读助手。请仅依据下面给出的书籍片段回答用户问题；"
                + "片段信息不足时如实说明“本书中未找到相关内容”，不要编造。"
                + "回答用中文，简洁、分点，控制在 300 字以内。";
        String prompt = system + "\n\n书籍片段：\n" + ctx + "\n用户问题：" + question;
        String answer = doubaoClient.chat(
                List.of(new DoubaoClient.ChatMessage("user", prompt)), 0.3, 800);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer);
        data.put("citations", citations);
        return Result.ok(data);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
