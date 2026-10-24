package com.readingnotes.build;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.BuildProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 构建前切片试算与预览校验（需求 F2.3）。
 *
 * <h3>解决什么问题</h3>
 * 原实现只能"构建完再看切片"，想换粒度就得整本重跑一遍（400 页约 3 分钟 + Embedding 费用）。
 * 这里把「解析 → 章节识别 → 段落聚合 → 切片」全部放在内存里跑一遍，<b>不落库、不调 Embedding</b>，
 * 前端拿到切片数、字数分布、章节覆盖率、抽样正文后，再决定用粗/中/细哪一档正式构建。
 *
 * <h3>解析缓存</h3>
 * 解析是这一步里唯一的重活（400 页约 3~8 秒）。按 {@code bookId + 文件长度 + 修改时间} 缓存
 * 解析结果（LRU 4 本），于是"粗→中→细"三档切换只需重跑毫秒级的切片，演示时是秒开的。
 * 构建任务也复用同一份缓存，重建（rebuild）不再重复解析。
 *
 * <h3>为什么不缓存切片结果</h3>
 * 切片只有几千个小对象、重算只要几毫秒，缓存收益极低；而缓存会让"文件被替换后拿到旧切片"
 * 这类问题变得难查。只缓存解析产物，边界清晰。
 */
@Slf4j
@Service
public class BuildPreviewService {

    /** 解析缓存容量（按书计）。单用户本地部署，同时翻 3~4 本足够 */
    private static final int CACHE_CAPACITY = 4;

    private final PdfTextExtractor extractor;
    private final ChapterRecognizer recognizer;
    private final ChapterFlowBuilder flowBuilder;
    private final SemanticChunker chunker;
    private final BuildProperties props;

    /** 解析缓存：accessOrder=true 的 LRU，超过 4 本自动淘汰最久未用的一本 */
    private final Map<String, ParsedBook> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ParsedBook> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    public BuildPreviewService(PdfTextExtractor extractor,
                               ChapterRecognizer recognizer,
                               ChapterFlowBuilder flowBuilder,
                               SemanticChunker chunker,
                               BuildProperties props) {
        this.extractor = extractor;
        this.recognizer = recognizer;
        this.flowBuilder = flowBuilder;
        this.chunker = chunker;
        this.props = props;
    }

    // =========================================================================
    // 解析缓存
    // =========================================================================

    /**
     * 一次解析的全部产物（构建与预览共用）。
     * <p>
     * {@code cached=true} 表示本次命中缓存，仅用于响应体里的 {@code cached_parse} 字段，
     * 不影响下游计算。
     */
    public static class ParsedBook {
        /** 逐行文本（含页码、字号），章节识别与段落聚合的输入 */
        private final List<TextLine> lines;
        private final int totalPages;
        /** 去空白后的正文总字数 */
        private final int charCount;
        private final ChapterRecognizer.Recognized recognized;
        private final ChapterFlowBuilder.Flow flow;
        private final boolean cached;

        ParsedBook(List<TextLine> lines, int totalPages, int charCount,
                   ChapterRecognizer.Recognized recognized, ChapterFlowBuilder.Flow flow, boolean cached) {
            this.lines = lines;
            this.totalPages = totalPages;
            this.charCount = charCount;
            this.recognized = recognized;
            this.flow = flow;
            this.cached = cached;
        }

        public List<TextLine> getLines() {
            return lines;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public int getCharCount() {
            return charCount;
        }

        public ChapterRecognizer.Recognized getRecognized() {
            return recognized;
        }

        public ChapterFlowBuilder.Flow getFlow() {
            return flow;
        }

        public boolean isCached() {
            return cached;
        }
    }

    /**
     * 取解析结果（优先命中缓存）。构建任务与预览共用，避免重复解析。
     *
     * @throws BusinessException 扫描件/加密/损坏时抛出对应业务异常
     */
    public ParsedBook parse(String bookId, File pdf) throws IOException {
        String key = cacheKey(bookId, pdf);
        ParsedBook hit = cache.get(key);
        if (hit != null) {
            return new ParsedBook(hit.lines, hit.totalPages, hit.charCount,
                    hit.recognized, hit.flow, true);
        }
        long t0 = System.currentTimeMillis();
        PdfTextExtractor.ExtractResult extracted = extractor.extract(pdf);
        if (extracted.isScanned()) {
            throw new BusinessException(ErrorCode.SCANNED_PDF);
        }
        int pages = extracted.getTotalPages();
        if (pages > props.getMaxPages()) {
            throw new BusinessException(ErrorCode.PDF_TOO_MANY_PAGES,
                    "本书共 " + pages + " 页，超过 " + props.getMaxPages() + " 页上限");
        }
        ChapterRecognizer.Recognized recognized = recognizer.recognize(extracted.getLines());
        // ChapterFlowBuilder 内部有"上一行是否句末"的状态字段，并发调用会互相干扰，
        // 这里显式串行化（build 本身是毫秒级，不影响吞吐）
        ChapterFlowBuilder.Flow flow;
        synchronized (flowBuilder) {
            flow = flowBuilder.build(extracted.getLines(), recognized, pages);
        }
        ParsedBook parsed = new ParsedBook(extracted.getLines(), pages,
                extracted.getCharCount(), recognized, flow, false);
        cache.put(key, parsed);
        log.info("parse cached bookId={} pages={} lines={} chars={} elapsed={}ms",
                bookId, pages, extracted.getLines().size(), extracted.getCharCount(),
                System.currentTimeMillis() - t0);
        return parsed;
    }

    /**
     * 缓存键：bookId + 文件长度 + 修改时间。
     * <p>
     * 带上后两者，文件被替换（重新上传/手工覆盖）时键自然变化，无需显式清理也能避免读到旧解析。
     */
    private static String cacheKey(String bookId, File pdf) {
        return bookId + "|" + pdf.length() + "|" + pdf.lastModified();
    }

    /** 文件被替换/删除后让缓存立即失效（重建、重新上传时调用） */
    public void invalidate(String bookId) {
        synchronized (cache) {
            cache.keySet().removeIf(k -> k.startsWith(bookId + "|"));
        }
    }

    // =========================================================================
    // 试算预览
    // =========================================================================

    /** 粒度档位 → 目标片长（需求 F2.3：粗 1000 / 中 600 / 细 300） */
    public static Integer sizeOfGranularity(String granularity) {
        if (granularity == null) {
            return null;
        }
        return switch (granularity.toLowerCase()) {
            case "coarse", "粗" -> 1000;
            case "medium", "中" -> 600;
            case "fine", "细" -> 300;
            default -> null;
        };
    }

    /**
     * 按指定粒度试算切片统计（不落库、不调用 Embedding）。
     *
     * @param bookId    书籍 id
     * @param pdf       原书文件
     * @param chunkSize 目标片长；null 用默认值
     * @param overlap   重叠字数；null 按片长的 1/6 估算（600→100，与默认一致）
     */
    public Map<String, Object> preview(String bookId, File pdf, Integer chunkSize, Integer overlap)
            throws IOException {
        int size = clampSize(chunkSize == null ? 600 : chunkSize);
        int ov = overlap != null ? Math.max(0, Math.min(overlap, size / 3)) : defaultOverlap(size);

        long t0 = System.currentTimeMillis();
        ParsedBook parsed = parse(bookId, pdf);
        SemanticChunker.ChunkPlan plan = chunker.chunk(bookId, parsed.getFlow().getParagraphs(), size, ov);
        long cost = System.currentTimeMillis() - t0;

        List<SemanticChunker.Chunk> chunks = plan.getChunks();
        int[] counts = chunks.stream().mapToInt(SemanticChunker.Chunk::getWordCount).sorted().toArray();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", bookId);
        data.put("dry_run", true);
        data.put("granularity", granularityName(size));
        data.put("chunk_size", size);
        data.put("overlap", ov);
        data.put("page_count", parsed.getTotalPages());
        data.put("total_chars", parsed.getCharCount());
        data.put("chunk_count", chunks.size());
        data.put("batch_size", plan.getBatchSize());
        data.put("total_batches", plan.getTotalBatches());
        data.put("cached_parse", parsed.isCached());
        data.put("elapsed_ms", cost);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("min", counts.length == 0 ? 0 : counts[0]);
        stats.put("p50", percentile(counts, 0.50));
        stats.put("p90", percentile(counts, 0.90));
        stats.put("max", counts.length == 0 ? 0 : counts[counts.length - 1]);
        stats.put("avg", counts.length == 0 ? 0
                : (int) Math.round(Arrays.stream(counts).average().orElse(0)));
        data.put("chunk_stats", stats);

        data.put("chapter", chapterView(parsed));
        data.put("samples", samples(chunks));
        data.put("warnings", warnings(chunks.size(), counts, parsed, plan));
        data.put("granularity_options", List.of(
                Map.of("key", "coarse", "label", "粗（1000 字）", "chunk_size", 1000, "overlap", 100),
                Map.of("key", "medium", "label", "中（600 字）", "chunk_size", 600, "overlap", 100),
                Map.of("key", "fine", "label", "细（300 字）", "chunk_size", 300, "overlap", 60)));
        // Embedding 粗估：每批一次网络往返，按 2s/批（含偶发 429 退避）+ 固定开销，仅用于前端提示
        data.put("estimated_seconds", Math.max(5, plan.getTotalBatches() * 2 + 8));
        return data;
    }

    private Map<String, Object> chapterView(ParsedBook parsed) {
        ChapterRecognizer.Recognized rec = parsed.getRecognized();
        List<ChapterRecognizer.Heading> headings = rec.getHeadings();
        List<Map<String, Object>> tree = new ArrayList<>();
        int maxLevel = 0;
        for (ChapterRecognizer.Heading h : headings) {
            maxLevel = Math.max(maxLevel, h.getLevel());
            if (tree.size() >= 300) {
                break;   // 目录可能上千条，预览只给前 300 条，避免响应体过大
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", h.getLevel());
            m.put("chapter_path", h.getChapterPath());
            m.put("title", h.getTitle());
            m.put("page", h.getPage());
            tree.add(m);
        }
        Map<String, Object> chapter = new LinkedHashMap<>();
        chapter.put("detected", !headings.isEmpty());
        chapter.put("title_count", headings.size());
        chapter.put("coverage", round2(rec.getRecognizedRate()));
        chapter.put("max_level", maxLevel);
        chapter.put("body_font_size", rec.getBodyFontSize());
        chapter.put("tree", tree);
        chapter.put("tree_truncated", headings.size() > tree.size());
        return chapter;
    }

    private List<Map<String, Object>> samples(List<SemanticChunker.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<Integer> picks = new LinkedList<>();
        int n = chunks.size();
        for (int i : new int[]{0, n / 4, n / 2, n * 3 / 4, n - 1}) {
            if (i >= 0 && i < n && !picks.contains(i)) {
                picks.add(i);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(picks.size());
        for (int i : picks) {
            SemanticChunker.Chunk c = chunks.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", c.getPosition());
            m.put("chunk_id", c.getChunkId());
            m.put("page", c.getPage());
            m.put("end_page", c.getEndPage());
            m.put("chapter_path", c.getChapterPath());
            m.put("word_count", c.getWordCount());
            String content = c.getContent();
            m.put("preview", content.length() <= 120 ? content : content.substring(0, 120) + "…");
            out.add(m);
        }
        return out;
    }

    private List<String> warnings(int chunkCount, int[] counts, ParsedBook parsed,
                                  SemanticChunker.ChunkPlan plan) {
        List<String> warnings = new ArrayList<>();
        double rate = parsed.getRecognized().getRecognizedRate();
        if (parsed.getRecognized().getHeadings().isEmpty() || rate < props.getChapterRateWarnThreshold()) {
            warnings.add("本书未识别到完整章节结构（覆盖率 " + Math.round(rate * 100)
                    + "%），问答将使用纯向量检索");
        }
        if (chunkCount > props.getChunkSoftLimit()) {
            warnings.add("切片数 " + chunkCount + " 超过 " + props.getChunkSoftLimit()
                    + " 片软上限，建议改用「粗」粒度或拆分上传");
        }
        if (plan.isTruncated()) {
            warnings.add("本书内容超过硬上限（" + props.getChunkHardLimit()
                    + " 片），构建时会自动调粗粒度，仍超限则会失败");
        }
        if (counts.length > 0 && percentile(counts, 0.5) < 200) {
            warnings.add("中位片长仅 " + percentile(counts, 0.5)
                    + " 字，切片偏碎：可能存在表格/目录残留，建议抽检下方样本");
        }
        if (counts.length > 0 && percentile(counts, 0.5) > 0
                && percentile(counts, 0.9) / (double) percentile(counts, 0.5) > 2.0) {
            warnings.add("片长分布不均（p90/p50 > 2），检索精度可能受影响，可尝试更细的粒度");
        }
        return warnings;
    }

    private int clampSize(int size) {
        return Math.max(props.getChunkSizeMin(), Math.min(props.getChunkSizeMax(), size));
    }

    private int defaultOverlap(int size) {
        return size <= 300 ? 60 : 100;
    }

    private static String granularityName(int size) {
        if (size >= 900) {
            return "coarse";
        }
        if (size <= 400) {
            return "fine";
        }
        return "medium";
    }

    private static int percentile(int[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.floor(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
