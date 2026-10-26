package com.readingnotes.ai;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.TraceContext;
import com.readingnotes.config.RagProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三阶段检索流水线（需求 §4.2）：① 查询理解 → ② 向量召回（可加章节/页码元数据过滤）→ ③ 阈值兜底拒答。
 * <p>
 * Rerank 阶段当前只做截断（取召回序的前 N 条），真正的重排模型待 F3 接入；
 * 阈值兜底是三段式防幻觉的最后一层：Top-1 相似度不足时直接返回 40002，不让模型自由发挥。
 * <p>
 * <b>接线状态</b>：本类尚未被任何 Controller 引用，属于 F3 问答的预留实现；
 * 改签名前请先确认 F3 是否已接入，避免影响并行开发。
 */
@Slf4j
@Component
public class RagPipeline {

    private final ChromaClient chroma;
    private final EmbeddingProvider embedding;
    private final RagProperties props;

    public RagPipeline(ChromaClient chroma, EmbeddingProvider embedding, RagProperties props) {
        this.chroma = chroma;
        this.embedding = embedding;
        this.props = props;
    }

    // 章节/页码定位词（"5.2节""第12页"）
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(第?\\s*([0-9]+(?:\\.[0-9]+)*)\\s*(?:章|节))");
    private static final Pattern PAGE_PATTERN = Pattern.compile("第\\s*([0-9]+)\\s*页");

    /**
     * 单集合检索入口（书本内问答 F3.2）
     *
     * @param collection Chroma 集合名（book_{book_id} / notes_all）
     * @param question   用户问题（已做多轮改写）
     * @return 召回片段（按相似度降序，已应用阈值兜底）
     */
    public RetrieveResult retrieve(String collection, String question) {
        // ① 查询理解：识别章节/页码线索 → 生成元数据过滤
        Map<String, Object> where = null;
        if (props.isEnableMetadataFilter()) {
            where = buildWhere(question);
        }

        // ② 向量召回 Top-K
        List<Float> qVec = embedding.embed(List.of(question)).get(0);
        List<ChromaClient.SearchHit> hits =
                chroma.query(collection, qVec, where, props.getRecallTopK());

        // ③ 阈值兜底：Top-1 相似度（distance→cosine）低于阈值直接拒答
        double top1 = hits.isEmpty() ? 0.0 : cosineFromDistance(hits.get(0).getDistance());
        if (top1 < props.getSimilarityThreshold()) {
            log.info("below threshold top1={} threshold={} question={} trace={}",
                    top1, props.getSimilarityThreshold(), question, TraceContext.current());
            return new RetrieveResult(true, List.of(), where);
        }

        // Rerank 精排（F5.2 enable_rerank；默认取召回 Top-N 交给后续 LLM）
        List<ChromaClient.SearchHit> reranked = hits.subList(0, Math.min(hits.size(), props.getRerankTopN()));
        return new RetrieveResult(false, reranked, where);
    }

    /** 章节/页码元数据过滤条件构造 */
    private Map<String, Object> buildWhere(String question) {
        Matcher cm = CHAPTER_PATTERN.matcher(question);
        if (cm.find()) {
            String path = cm.group(2);
            // 前缀匹配：问"1.2"应命中 1.2、1.2.x
            Map<String, Object> where = new HashMap<>();
            where.put("chapter_path", Map.of("$starts_with", path));
            return where;
        }
        Matcher pm = PAGE_PATTERN.matcher(question);
        if (pm.find()) {
            Map<String, Object> where = new HashMap<>();
            where.put("page", Integer.parseInt(pm.group(1)));
            return where;
        }
        return null;
    }

    /** Chroma 返回 distance（余弦距离），转相似度 = 1 - distance */
    private double cosineFromDistance(double distance) {
        return Math.max(0.0, 1.0 - distance);
    }

    /** 拒绝时也可按需求返回统一文案（错误码 40002） */
    public BusinessException notMentioned() {
        return new BusinessException(ErrorCode.NOT_MENTIONED);
    }

    /** 检索结果：refused=true 时 hits 必为空，调用方应直接回 40002 而不是继续拼 prompt */
    @Data
    public static class RetrieveResult {
        /** true = 应拒答（相似度低于阈值） */
        private final boolean refused;
        private final List<ChromaClient.SearchHit> hits;
        /** 本次实际使用的元数据过滤条件（供日志/前端展示） */
        private final Map<String, Object> whereUsed;

        public RetrieveResult(boolean refused, List<ChromaClient.SearchHit> hits, Map<String, Object> whereUsed) {
            this.refused = refused;
            this.hits = hits;
            this.whereUsed = whereUsed;
        }
    }
}
