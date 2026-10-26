package com.readingnotes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.ai.RagPipeline;
import com.readingnotes.config.ModelProperties;
import com.readingnotes.dto.eval.FailItemDTO;
import com.readingnotes.repository.EvalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评估异步执行器 — 独立 Bean 解决 @Async 自调用失效问题
 * <p>
 * 完整 RAG 链路：
 * 1. 从 eval_datasets 取题
 * 2. 调用 RagPipeline.retrieve() 向量召回（计算 recall_at_5/10）
 * 3. 用召回 chunks 拼 prompt，调用 DoubaoClient.chat 生成答案（计算 answer/refusal/citation + 耗时）
 * 4. 汇总 7 项指标写入 eval_results
 */
@Slf4j
@Component
public class EvalTaskRunner {

    private final EvalRepository evalRepo;
    private final RagPipeline ragPipeline;
    private final DoubaoClient doubao;
    private final ModelProperties modelProps;
    private final ObjectMapper objectMapper;

    /** 拒答关键词集合：当 ground_truth 是"本书中未提及相关内容"时，LLM 答案命中这些词视为正确拒答 */
    private static final Set<String> REFUSAL_KEYWORDS = Set.of(
            "未提及", "未提到", "没有提到", "不在本书", "无法回答", "没有相关",
            "未涉及", "没有描述", "相关内容", "未记载", "未涵盖");

    /** 停用词（简单中文停用词表，用于关键词提取） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "与", "及", "或", "在", "是", "有", "我", "你", "他",
            "这", "那", "个", "不", "也", "都", "就", "把", "被", "从", "到", "对",
            "为", "以", "可", "能", "会", "要", "上", "下", "中", "之", "而", "但",
            "如何", "什么", "怎么", "为什么", "哪些", "哪个", "可以", "通过", "以及");

    public EvalTaskRunner(EvalRepository evalRepo, RagPipeline ragPipeline,
                          DoubaoClient doubao, ModelProperties modelProps,
                          ObjectMapper objectMapper) {
        this.evalRepo = evalRepo;
        this.ragPipeline = ragPipeline;
        this.doubao = doubao;
        this.modelProps = modelProps;
        this.objectMapper = objectMapper;
    }

    /** 异步执行评估 */
    @Async
    public void execute(String evalRunId, String datasetVersion, List<String> categories, int sampleSize) {
        try {
            // 取题
            List<EvalRepository.EvalItem> items = evalRepo.findItemsByCategories(categories, sampleSize);
            if (items.isEmpty()) {
                log.warn("eval {} no items matched, categories={}", evalRunId, categories);
            }

            // 累加器
            int pass = 0;
            int recall5Hit = 0, recall5Count = 0;
            int recall10Hit = 0, recall10Count = 0;
            int answerHit = 0, answerCount = 0;
            int refusalHit = 0, refusalCount = 0;
            int citationHit = 0, citationCount = 0;
            long totalFirstToken = 0;
            long totalFullAnswer = 0;

            List<FailItemDTO> failItems = new ArrayList<>();

            for (EvalRepository.EvalItem item : items) {
                try {
                    String collection = item.bookId != null ? "book_" + item.bookId : "notes_all";
                    long t0 = System.currentTimeMillis();

                    // ② 向量召回（Top-K）
                    RagPipeline.RetrieveResult result = ragPipeline.retrieve(collection, item.question);
                    var hits = result.getHits();
                    boolean refused = result.isRefused();

                    long firstTokenMs = System.currentTimeMillis() - t0; // 非流式，近似为检索+构造耗时
                    String got;

                    if (refused || hits.isEmpty()) {
                        // 拒答路径
                        got = "本书中未提及相关内容";
                    } else {
                        // ③ 构造 prompt 并调用 LLM
                        StringBuilder context = new StringBuilder();
                        for (int i = 0; i < hits.size(); i++) {
                            ChromaClient.SearchHit h = hits.get(i);
                            context.append("[").append(i + 1).append("] ").append(h.getDocument()).append("\n\n");
                        }
                        String prompt = "请基于以下参考资料回答问题，回答要简洁准确，不得编造。\n\n"
                                + "【参考资料】\n" + context
                                + "\n【问题】" + item.question + "\n【回答】";

                        DoubaoClient.ChatResult chat = doubao.chatWithUsage(
                                List.of(new DoubaoClient.ChatMessage("user", prompt)),
                                modelProps.getTemperature(),
                                modelProps.getMaxTokens());
                        got = chat.content() == null ? "" : chat.content().trim();
                    }

                    long fullAnswerMs = System.currentTimeMillis() - t0;
                    totalFirstToken += firstTokenMs;
                    totalFullAnswer += fullAnswerMs;

                    // ========== 指标计算 ==========
                    // 1. recall_at_5 / recall_at_10
                    int hit5 = hits.size() >= 5 ? 5 : hits.size();
                    int hit10 = hits.size() >= 10 ? 10 : hits.size();
                    boolean r5 = checkRecall(item, hits, hit5);
                    boolean r10 = checkRecall(item, hits, hit10);
                    if (item.expectedChunkIdsJson != null && !item.expectedChunkIdsJson.isBlank()) {
                        recall5Count++;
                        recall10Count++;
                        if (r5) recall5Hit++;
                        if (r10) recall10Hit++;
                    }

                    // 2. answer_accuracy：book_out_of_scope 走拒答判断，其他走关键词匹配
                    boolean answerOk;
                    if ("book_out_of_scope".equals(item.category)) {
                        refusalCount++;
                        answerOk = isRefusalAnswer(got);
                        if (answerOk) refusalHit++;
                    } else {
                        answerCount++;
                        answerOk = keywordMatch(item.groundTruthAnswer, got);
                        if (answerOk) answerHit++;
                    }

                    // 3. citation_accuracy：有 expected_citation 时才统计
                    if (item.expectedCitationPagesJson != null && !item.expectedCitationPagesJson.isBlank()) {
                        citationCount++;
                        if (checkCitation(item, hits, got)) citationHit++;
                    }

                    // 是否通过：非拒答类用 answerOk，拒答类用 refusal 判断
                    boolean passed = answerOk;
                    if (passed) pass++;
                    else {
                        FailItemDTO f = new FailItemDTO();
                        f.setItemId(item.itemId);
                        f.setCategory(item.category);
                        f.setQuestion(item.question);
                        f.setExpected(item.groundTruthAnswer);
                        f.setGot(truncate(got, 200));
                        f.setDiffNote(refused ? "检索阈值兜底拒答"
                                : (hits.isEmpty() ? "向量库无相关切片" : "答案与期望不符"));
                        failItems.add(f);
                    }

                } catch (Exception e) {
                    log.error("eval item failed item_id={}", item.itemId, e);
                    FailItemDTO f = new FailItemDTO();
                    f.setItemId(item.itemId);
                    f.setCategory(item.category);
                    f.setQuestion(item.question);
                    f.setExpected(item.groundTruthAnswer);
                    f.setGot("");
                    f.setDiffNote("执行异常: " + e.getMessage());
                    failItems.add(f);
                }
            }

            // 汇总指标
            int n = items.size();
            EvalRepository.EvalResult r = new EvalRepository.EvalResult();
            r.evalRunId = evalRunId;
            r.finishedAt = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            r.passItems = pass;
            r.recallAt5 = recall5Count == 0 ? 0.0 : (double) recall5Hit / recall5Count;
            r.recallAt10 = recall10Count == 0 ? 0.0 : (double) recall10Hit / recall10Count;
            r.answerAccuracy = answerCount == 0 ? 0.0 : (double) answerHit / answerCount;
            r.refusalAccuracy = refusalCount == 0 ? 0.0 : (double) refusalHit / refusalCount;
            r.citationAccuracy = citationCount == 0 ? 0.0 : (double) citationHit / citationCount;
            r.avgFirstTokenMs = n == 0 ? 0 : (int) (totalFirstToken / n);
            r.avgFullAnswerMs = n == 0 ? 0 : (int) (totalFullAnswer / n);
            r.failItemsJson = objectMapper.writeValueAsString(failItems);
            r.reportPath = null; // 报告文件生成逻辑可后续扩展

            evalRepo.updateResult(r);
            log.info("eval {} finished: pass={}/{} recall@5={} recall@10={} answer={} refusal={} citation={}",
                    evalRunId, pass, n, r.recallAt5, r.recallAt10,
                    r.answerAccuracy, r.refusalAccuracy, r.citationAccuracy);

        } catch (Exception e) {
            log.error("eval {} fatal error", evalRunId, e);
        }
    }

    // ====================================================================
    // 指标计算辅助
    // ====================================================================

    /** recall 判断：召回 hits 的前 n 个中是否命中 expected chunk */
    private boolean checkRecall(EvalRepository.EvalItem item, List<ChromaClient.SearchHit> hits, int n) {
        if (item.expectedChunkIdsJson == null || item.expectedChunkIdsJson.isBlank()) {
            // 没有期望 chunk id，退化为"召回内容是否包含期望答案关键词"
            String expected = item.groundTruthAnswer;
            for (int i = 0; i < Math.min(hits.size(), n); i++) {
                if (keywordMatch(expected, hits.get(i).getDocument())) return true;
            }
            return false;
        }
        Set<String> expectedIds = parseJsonStringArray(item.expectedChunkIdsJson);
        for (int i = 0; i < Math.min(hits.size(), n); i++) {
            if (expectedIds.contains(hits.get(i).getId())) return true;
        }
        return false;
    }

    /** citation 判断：答案或召回 chunks 是否引用了期望的页码/章节 */
    private boolean checkCitation(EvalRepository.EvalItem item,
                                  List<ChromaClient.SearchHit> hits, String got) {
        Set<String> expectedPages = parseJsonStringArray(item.expectedCitationPagesJson);
        if (expectedPages.isEmpty()) return true;
        for (String p : expectedPages) {
            if (got.contains(p)) return true;
            for (ChromaClient.SearchHit h : hits) {
                if (h.getDocument() != null && h.getDocument().contains(p)) return true;
            }
        }
        return false;
    }

    /** 判断是否为拒答回答 */
    private boolean isRefusalAnswer(String got) {
        if (got == null || got.isBlank()) return false;
        for (String kw : REFUSAL_KEYWORDS) {
            if (got.contains(kw)) return true;
        }
        return false;
    }

    /** 关键词匹配：提取期望答案的实词（≥2 字），看 got 是否覆盖了至少 50% */
    private boolean keywordMatch(String expected, String got) {
        if (expected == null || got == null) return false;
        Set<String> expectedWords = extractKeywords(expected);
        if (expectedWords.isEmpty()) return true; // 无法提取关键词则视为通过
        int hit = 0;
        for (String w : expectedWords) {
            if (got.contains(w)) hit++;
        }
        return (double) hit / expectedWords.size() >= 0.5;
    }

    /** 提取实词：按标点/空格切分，过滤单字和停用词 */
    private Set<String> extractKeywords(String text) {
        Set<String> words = new HashSet<>();
        String[] parts = text.split("[\\s，。、；：！？,.:;!?\"'（）()\\[\\]【】]+");
        for (String p : parts) {
            String w = p.trim();
            if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                words.add(w);
            }
        }
        // 对长词再做一次 2-gram 切片（中文无空格分词的兜底）
        if (words.isEmpty()) {
            for (int i = 0; i + 2 <= text.length(); i++) {
                String gram = text.substring(i, i + 2);
                if (!STOP_WORDS.contains(gram)) words.add(gram);
            }
        }
        return words;
    }

    /** 解析 JSON 字符串数组 ["id1","id2"] → Set */
    @SuppressWarnings("unchecked")
    private Set<String> parseJsonStringArray(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<String> list = objectMapper.readValue(json, List.class);
            return new HashSet<>(list);
        } catch (Exception e) {
            // 兜底：按逗号分隔
            String[] parts = json.replaceAll("[\\[\\]\"]", "").split(",");
            return new HashSet<>(Arrays.asList(parts));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
