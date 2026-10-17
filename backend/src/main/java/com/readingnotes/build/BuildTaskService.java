package com.readingnotes.build;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.TraceContext;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.BuildProperties;
import com.readingnotes.config.DoubaoProperties;
import com.readingnotes.config.RagProperties;
import com.readingnotes.entity.Book;
import com.readingnotes.entity.Chapter;
import com.readingnotes.entity.Chunk;
import com.readingnotes.repository.BookRepository;
import com.readingnotes.repository.BuildProgressRepository;
import com.readingnotes.repository.ChapterRepository;
import com.readingnotes.repository.ChunkRepository;
import com.readingnotes.util.Ids;
import com.readingnotes.util.Times;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 构建任务编排（需求 F2 / F2.5 断点续传 / §4.6.1 书籍状态机）。
 *
 * <h3>阶段与进度</h3>
 * <pre>
 * PARSE(10) → RECOGNIZE(20) → CHUNK(30) → EMBED(30~90，按批次推进) → INGEST(100) → READY
 * </pre>
 *
 * <h3>断点续传怎么做</h3>
 * 切片阶段就把所有切片连同 {@code embedding_batch_id} 一次性落库，因此"进度"天然可恢复：
 * <ul>
 *   <li>构建中崩溃 → 重启后 {@code books.status} 仍是 BUILDING，由 {@link BuildMaintenanceJob}
 *       在 {@code ApplicationReadyEvent} 时自动按 {@code build_progress.last_completed_batch} 续跑；</li>
 *   <li>手动重试 → {@code POST /build/retry} 复用已落库的切片，只补未完成的批次；</li>
 *   <li>Chroma 用 upsert（id = chunk_id），重复写同一批不会产生重复向量。</li>
 * </ul>
 *
 * <h3>为什么串行</h3>
 * 单 worker 队列（{@link com.readingnotes.config.AsyncConfig#buildExecutor}），避免多书同时
 * 向量化触发 Embedding 限流（需求 §6.5）。
 */
@Slf4j
@Service
public class BuildTaskService {

    /** 取消信号集合：book_id → true，worker 在批次边界检查 */
    private final Set<String> cancelFlags = ConcurrentHashMap.newKeySet();

    /** 正在执行构建的 book_id，防重复提交 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    private final BookRepository bookRepo;
    private final ChapterRepository chapterRepo;
    private final ChunkRepository chunkRepo;
    private final BuildProgressRepository progressRepo;
    private final PdfTextExtractor extractor;
    private final ChapterRecognizer recognizer;
    private final ChapterFlowBuilder flowBuilder;
    private final SemanticChunker chunker;
    private final DoubaoClient doubao;
    private final ChromaClient chroma;
    private final AppProperties appProps;
    private final BuildProperties buildProps;
    private final DoubaoProperties doubaoProps;
    private final RagProperties ragProps;
    private final Executor buildExecutor;

    public BuildTaskService(BookRepository bookRepo,
                            ChapterRepository chapterRepo,
                            ChunkRepository chunkRepo,
                            BuildProgressRepository progressRepo,
                            PdfTextExtractor extractor,
                            ChapterRecognizer recognizer,
                            ChapterFlowBuilder flowBuilder,
                            SemanticChunker chunker,
                            DoubaoClient doubao,
                            ChromaClient chroma,
                            AppProperties appProps,
                            BuildProperties buildProps,
                            DoubaoProperties doubaoProps,
                            RagProperties ragProps,
                            @Qualifier("buildExecutor") Executor buildExecutor) {
        this.bookRepo = bookRepo;
        this.chapterRepo = chapterRepo;
        this.chunkRepo = chunkRepo;
        this.progressRepo = progressRepo;
        this.extractor = extractor;
        this.recognizer = recognizer;
        this.flowBuilder = flowBuilder;
        this.chunker = chunker;
        this.doubao = doubao;
        this.chroma = chroma;
        this.appProps = appProps;
        this.buildProps = buildProps;
        this.doubaoProps = doubaoProps;
        this.ragProps = ragProps;
        this.buildExecutor = buildExecutor;
    }

    // =========================================================================
    // 提交
    // =========================================================================

    /**
     * 异步提交一次构建。
     *
     * @param bookId      书籍 id
     * @param chunkSize   切片粒度（null 用 rag.chunk-size 默认值）
     * @param overlap     重叠字数（null 用默认值）
     * @param rebuild     true = 重建（先删旧向量/切片/章节）；false = 首次构建或断点续跑
     */
    public void submit(String bookId, Integer chunkSize, Integer overlap, boolean rebuild) {
        if (!running.add(bookId)) {
            log.warn("build already running, skip submit bookId={}", bookId);
            return;
        }
        try {
            buildExecutor.execute(() -> {
                try {
                    runBuild(bookId, chunkSize, overlap, rebuild);
                } catch (Exception e) {
                    log.error("build task crashed bookId={} trace={}", bookId, TraceContext.current(), e);
                    failQuietly(bookId, ErrorCode.BUILD_FAILED, "构建任务异常：" + e.getMessage());
                } finally {
                    running.remove(bookId);
                    cancelFlags.remove(bookId);
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(bookId);
            log.error("build queue full, reject bookId={}", bookId);
            bookRepo.markFailed(bookId, String.valueOf(ErrorCode.BUILD_FAILED.getCode()),
                    "构建队列已满，请稍后重试", Times.now());
            throw new BusinessException(ErrorCode.BUILD_FAILED, "构建队列已满，请稍后重试");
        }
    }

    /**
     * 构建是否在跑（删除书籍前判断用，需求 §6.5 多 Tab 并发）
     */
    public boolean isRunning(String bookId) {
        return running.contains(bookId);
    }

    /**
     * 请求取消：置标记，worker 在下一个批次边界退出。
     * 已完成的批次向量保留，后续 retry 可续跑（API 文档 §6.3）。
     */
    public void cancel(String bookId) {
        cancelFlags.add(bookId);
        log.info("build cancel requested bookId={}", bookId);
    }

    /** 阻塞等待任务退出（删除书籍时用，最多等 timeoutMs） */
    public boolean awaitStop(String bookId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (running.contains(bookId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !running.contains(bookId);
    }

    // =========================================================================
    // 主流程
    // =========================================================================

    void runBuild(String bookId, Integer chunkSize, Integer overlap, boolean rebuild) {
        Book book = bookRepo.findOrNull(bookId);
        if (book == null) {
            log.warn("build skipped, book not found bookId={}", bookId);
            return;
        }
        long t0 = System.currentTimeMillis();
        String now = Times.now();
        log.info("==== build start bookId={} title={} rebuild={} ====", bookId, book.getTitle(), rebuild);

        try {
            // ---------- 0. 准备：状态置 BUILDING，重建时清理旧数据 ----------
            if (rebuild) {
                bookRepo.updateStage(bookId, "REBUILDING", "PARSE", 10, now);
                cleanPreviousArtifacts(bookId);
            } else {
                bookRepo.updateStage(bookId, "BUILDING", "PARSE", 10, now);
            }

            BuildProgressSnapshot snap = loadOrInitProgress(bookId, now);
            File pdf = new File(appProps.getUploadsDir(), bookId + ".pdf");
            if (!pdf.isFile()) {
                fail(bookId, ErrorCode.BUILD_FAILED, "PDF 文件不存在：" + pdf.getName());
                return;
            }

            // ---------- 1. 解析（10 → 20） ----------
            PdfTextExtractor.ExtractResult extracted = extractor.extract(pdf);
            if (extracted.isScanned()) {
                log.info("scanned pdf detected bookId={} chars={}", bookId, extracted.getCharCount());
                fail(bookId, ErrorCode.SCANNED_PDF, "未检测到可复制文字，可能是扫描件");
                return;
            }
            if (checkCancelled(bookId)) {
                bookRepo.markCancelled(bookId, Times.now());
                return;
            }
            bookRepo.updateStage(bookId, currentStatus(rebuild), "RECOGNIZE", 20, Times.now());

            // ---------- 2. 章节识别（20 → 30） ----------
            ChapterRecognizer.Recognized recognized = recognizer.recognize(extracted.getLines());
            if (checkCancelled(bookId)) {
                bookRepo.markCancelled(bookId, Times.now());
                return;
            }
            bookRepo.updateStage(bookId, currentStatus(rebuild), "CHUNK", 30, Times.now());

            // ---------- 3. 切片（30） ----------
            // 断点续传：切片已落库且批次规划一致 → 直接复用，不重新解析
            int size = chunkSize != null ? chunkSize : defaultChunkSize();
            int ov = overlap != null ? overlap : defaultOverlap();
            boolean reuseChunks = !rebuild && snap.canResume
                    && snap.totalBatches != null && snap.planned != null
                    && snap.planned > 0 && chunkRepo.countByBook(bookId, null) == snap.planned;

            ChapterFlowBuilder.Flow flow = null;
            double chapterRate;
            int plannedChunks;
            int totalBatches;

            if (reuseChunks) {
                plannedChunks = snap.planned;
                totalBatches = snap.totalBatches;
                chapterRate = snap.chapterRate == null ? 0 : snap.chapterRate;
                log.info("resume build bookId={} reuse chunks planned={} totalBatches={} fromBatch={}",
                        bookId, plannedChunks, totalBatches, snap.lastCompletedBatch);
            } else {
                flow = flowBuilder.build(extracted.getLines(), recognized, extracted.getTotalPages());
                chapterRate = flow.getRecognizedRate();
                // 章节率过低：记警告，构建继续（需求 F2.3 / 错误码 30003 是警告不是错误）
                if (flow.isHasStructure() && chapterRate < buildProps.getChapterRateWarnThreshold()) {
                    log.warn("chapter rate too low bookId={} rate={} — fallback to pure vector search",
                            bookId, chapterRate);
                }
                persistChapters(bookId, flow, extracted.getTotalPages());

                SemanticChunker.ChunkPlan plan =
                        chunker.chunk(bookId, flow.getParagraphs(), size, ov);
                persistChunks(bookId, plan);
                plannedChunks = plan.getChunks().size();
                totalBatches = plan.getTotalBatches();
                if (plannedChunks == 0) {
                    fail(bookId, ErrorCode.BUILD_FAILED, "切片结果为空，请检查 PDF 内容");
                    return;
                }
                if (plan.isTruncated() || plannedChunks > buildProps.getChunkSoftLimit()) {
                    // 软上限：不阻塞构建，只在预览页红色警告（需求 F2.2）
                    log.warn("chunks exceed soft limit bookId={} chunks={} softLimit={}",
                            bookId, plannedChunks, buildProps.getChunkSoftLimit());
                }
                progressRepo.savePlan(bookId, totalBatches, plannedChunks, chapterRate, Times.now());
                // 新切片 → 断点归零
                snap.lastCompletedBatch = 0;
            }

            if (checkCancelled(bookId)) {
                bookRepo.markCancelled(bookId, Times.now());
                return;
            }

            // ---------- 4. 向量化 + 入库（30 → 90 → 100） ----------
            IngestOutcome outcome = embedAndIngest(bookId, totalBatches, snap.lastCompletedBatch);
            if (outcome.cancelled) {
                bookRepo.markCancelled(bookId, Times.now());
                log.info("build cancelled bookId={} completedBatches={}",
                        bookId, outcome.lastCompletedBatch);
                return;
            }
            if (!outcome.failedBatches.isEmpty()) {
                progressRepo.saveFailedBatches(bookId, toJsonArray(outcome.failedBatches), Times.now());
                String code = String.valueOf(ErrorCode.BUILD_FAILED.getCode());
                if (outcome.lastError != null && outcome.lastError.getCode() == ErrorCode.EMBED_TIMEOUT.getCode()) {
                    code = String.valueOf(ErrorCode.EMBED_TIMEOUT.getCode());
                }
                fail(bookId, code, "批次 " + outcome.failedBatches.size() + " 个失败（"
                        + outcome.failedBatches + "），可点击重试续跑");
                return;
            }

            // ---------- 5. 收尾（100） ----------
            bookRepo.markReady(bookId, plannedChunks, chapterRate,
                    doubaoProps.getEmbeddingModel(), doubaoProps.getEmbeddingDim(),
                    extracted.getTotalPages(), Times.now());
            log.info("==== build done bookId={} chunks={} batches={} elapsed={}ms ====",
                    bookId, plannedChunks, totalBatches, System.currentTimeMillis() - t0);

        } catch (BusinessException e) {
            log.warn("build business error bookId={} code={} msg={}", bookId, e.getCode(), e.getMessage());
            fail(bookId, String.valueOf(e.getCode()), e.getMessage());
        } catch (java.io.IOException e) {
            log.error("build io error bookId={} trace={}", bookId, TraceContext.current(), e);
            fail(bookId, String.valueOf(ErrorCode.BUILD_FAILED.getCode()),
                    "PDF 解析失败，文件可能已损坏：" + e.getMessage());
        } catch (Exception e) {
            log.error("build unexpected error bookId={} trace={}", bookId, TraceContext.current(), e);
            fail(bookId, String.valueOf(ErrorCode.BUILD_FAILED.getCode()),
                    "构建失败：" + e.getMessage());
        }
    }

    // =========================================================================
    // 向量化 + 入库
    // =========================================================================

    private static class IngestOutcome {
        boolean cancelled;
        int lastCompletedBatch;
        List<Integer> failedBatches = new ArrayList<>();
        BusinessException lastError;
    }

    /**
     * 逐批向量化并写入 Chroma。
     * <p>
     * 每批：取库中该批切片 → Embedding(16 条) → Chroma upsert → 标记 DONE → 推进断点。
     * 单批失败只记 failed_batches 继续下一批（避免一批坏数据拖垮整本），
     * 全部跑完后若仍有失败批次 → 整书 FAILED，retry 时只补这些批次。
     */
    private IngestOutcome embedAndIngest(String bookId, int totalBatches, int startBatch) {
        IngestOutcome out = new IngestOutcome();
        out.lastCompletedBatch = startBatch;
        String collection = collectionName(bookId);

        // Chroma 集合创建 + 维度校验（需求 F2.4）
        chroma.createCollection(collection, doubaoProps.getEmbeddingDim());

        List<Chunk> all = chunkRepo.listByBook(bookId);
        Map<Integer, List<Chunk>> batches = new LinkedHashMap<>();
        for (Chunk c : all) {
            int b = c.getEmbeddingBatchId() == null ? 0 : c.getEmbeddingBatchId();
            batches.computeIfAbsent(b, k -> new ArrayList<>()).add(c);
        }

        int batchSize = buildProps.getEmbeddingBatchSize();
        for (int b = startBatch; b < totalBatches; b++) {
            if (checkCancelled(bookId)) {
                out.cancelled = true;
                return out;
            }
            List<Chunk> batch = batches.get(b);
            if (batch == null || batch.isEmpty()) {
                // 空批次视为已完成，直接跳过（批次规划与切片数不一致时的兜底）。
                // 注意：必须同时推进内存断点，否则 lastCompletedBatch 会停在旧值，
                // 中断后从这里续跑又会从同一批开始 —— 无进展的死循环。
                progressRepo.markBatchCompleted(bookId, b, chunkRepo.countDone(bookId), Times.now());
                out.lastCompletedBatch = b + 1;
                continue;
            }
            // 已全部 DONE 的批次跳过（续跑时减少重复调用）
            boolean allDone = batch.stream().allMatch(c -> "DONE".equals(c.getEmbeddingStatus()));
            if (allDone) {
                out.lastCompletedBatch = b + 1;
                progressRepo.markBatchCompleted(bookId, b, chunkRepo.countDone(bookId), Times.now());
                int p = BookRepository.embedProgress(b + 1, totalBatches);
                bookRepo.updateProgress(bookId, p, Times.now());
                continue;
            }

            try {
                List<String> texts = batch.stream().map(Chunk::getContent).toList();
                boolean[] rateLimited = new boolean[1];
                List<List<Float>> vectors = doubao.embed(texts, rateLimited);

                List<String> ids = batch.stream().map(Chunk::getChunkId).toList();
                List<String> docs = new ArrayList<>(texts);
                List<Map<String, Object>> metas = new ArrayList<>(batch.size());
                for (Chunk c : batch) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("book_id", bookId);
                    m.put("chunk_id", c.getChunkId());
                    m.put("page", c.getPage() == null ? 0 : c.getPage());
                    m.put("chapter_path", c.getChapterPath() == null ? "" : c.getChapterPath());
                    m.put("chapter_title", c.getChapterTitle() == null ? "" : c.getChapterTitle());
                    m.put("chunk_type", "book");
                    m.put("position", c.getPosition());
                    metas.add(m);
                }
                chroma.upsert(collection, ids, vectors, docs, metas);
                chunkRepo.markBatchDone(bookId, b);

                out.lastCompletedBatch = b + 1;
                progressRepo.markBatchCompleted(bookId, b, chunkRepo.countDone(bookId), Times.now());
                int progress = BookRepository.embedProgress(b + 1, totalBatches);
                bookRepo.updateProgress(bookId, progress, Times.now());

                if ((b + 1) % 10 == 0 || b + 1 == totalBatches) {
                    log.info("embed progress bookId={} batch={}/{} progress={}%",
                            bookId, b + 1, totalBatches, progress);
                }
            } catch (BusinessException e) {
                log.warn("embed batch failed bookId={} batch={} code={} msg={}",
                        bookId, b, e.getCode(), e.getMessage());
                chunkRepo.markBatchFailed(bookId, b);
                out.failedBatches.add(b);
                out.lastError = e;
                // Chroma 整体不可用 / key 未配置 → 后续批次必然全失败，直接中止
                if (e.getCode() == ErrorCode.CHROMA_DOWN.getCode()
                        || e.getCode() == ErrorCode.DOUBAO_DOWN.getCode()
                        || e.getCode() == 70003) {
                    log.error("fatal dependency error, abort remaining batches bookId={}", bookId);
                    break;
                }
            }
        }
        // Chroma 写入完成即视为入库阶段（需求：INGEST 90→100）
        if (!out.cancelled && out.failedBatches.isEmpty()) {
            bookRepo.updateStage(bookId, currentStatus(false), "INGEST", 95, Times.now());
        }
        return out;
    }

    // =========================================================================
    // 数据落库
    // =========================================================================

    /** 章节落库（重建时先清空） */
    private void persistChapters(String bookId, ChapterFlowBuilder.Flow flow, int totalPages) {
        chapterRepo.deleteByBook(bookId);
        Map<String, Chapter> byPath = new LinkedHashMap<>();
        int offset = 0;
        for (ChapterFlowBuilder.Section para : flow.getParagraphs()) {
            String path = para.getChapterPath();
            int chars = para.getWordCount();
            if (path != null) {
                Chapter c = byPath.get(path);
                if (c == null) {
                    c = new Chapter();
                    c.setChapterId(Ids.chapterId());
                    c.setBookId(bookId);
                    c.setChapterPath(path);
                    c.setTitle(para.getChapterTitle());
                    c.setLevel(Math.max(1, Math.min(3, para.getLevel())));
                    c.setStartPage(para.getStartPage());
                    c.setEndPage(para.getEndPage());
                    c.setTextOffset(offset);
                    c.setWordCount(0);
                    byPath.put(path, c);
                } else {
                    c.setEndPage(Math.max(c.getEndPage() == null ? 0 : c.getEndPage(), para.getEndPage()));
                }
                c.setWordCount(c.getWordCount() + chars);
            }
            offset += chars;
        }
        // 无章节结构时也落一条"全书"章节，便于 GET /chapters 有东西可展示
        if (byPath.isEmpty() && offset > 0) {
            Chapter whole = new Chapter();
            whole.setChapterId(Ids.chapterId());
            whole.setBookId(bookId);
            whole.setChapterPath("");
            whole.setTitle("（未识别到章节结构）");
            whole.setLevel(1);
            whole.setStartPage(1);
            whole.setEndPage(totalPages);
            whole.setTextOffset(0);
            whole.setWordCount(offset);
            byPath.put("", whole);
        }
        chapterRepo.batchInsert(new ArrayList<>(byPath.values()));
        log.info("chapters persisted bookId={} count={}", bookId, byPath.size());
    }

    /** 切片落库（重建/重新切片时先清空旧切片） */
    private void persistChunks(String bookId, SemanticChunker.ChunkPlan plan) {
        chunkRepo.deleteByBook(bookId);
        String now = Times.now();
        List<Chunk> entities = new ArrayList<>(plan.getChunks().size());
        for (SemanticChunker.Chunk c : plan.getChunks()) {
            Chunk e = new Chunk();
            e.setChunkId(c.getChunkId());
            e.setBookId(bookId);
            e.setChunkType("book");
            e.setContent(c.getContent());
            e.setPage(c.getPage());
            e.setChapterPath(c.getChapterPath());
            e.setChapterTitle(c.getChapterTitle());
            e.setPosition(c.getPosition());
            e.setWordCount(c.getWordCount());
            e.setEmbeddingStatus("PENDING");
            e.setEmbeddingBatchId(c.getBatchId());
            entities.add(e);
        }
        chunkRepo.batchInsertBookChunks(bookId, entities, now);
        log.info("chunks persisted bookId={} count={}", bookId, entities.size());
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private static class BuildProgressSnapshot {
        boolean canResume;
        Integer totalBatches;
        Integer planned;
        Integer lastCompletedBatch = 0;
        Double chapterRate;
    }

    private BuildProgressSnapshot loadOrInitProgress(String bookId, String now) {
        BuildProgressSnapshot snap = new BuildProgressSnapshot();
        var p = progressRepo.findOrNull(bookId);
        if (p == null) {
            progressRepo.start(bookId, now);
            return snap;
        }
        snap.totalBatches = p.getTotalBatches();
        snap.planned = p.getChunkCountPlanned();
        snap.lastCompletedBatch = p.getLastCompletedBatch() == null ? 0 : p.getLastCompletedBatch();
        snap.chapterRate = p.getChapterRecognizedRate();
        // 有批次规划且断点未跑完 → 可续跑
        snap.canResume = p.getTotalBatches() != null
                && snap.lastCompletedBatch < p.getTotalBatches();
        return snap;
    }

    /** 重建：删 Chroma 集合 + 清 chapters/chunks 记录 */
    private void cleanPreviousArtifacts(String bookId) {
        try {
            chroma.deleteCollection(collectionName(bookId));
        } catch (Exception e) {
            log.warn("delete old collection failed (ignored) bookId={} {}", bookId, e.getMessage());
        }
        chunkRepo.deleteByBook(bookId);
        chapterRepo.deleteByBook(bookId);
        progressRepo.deleteByBook(bookId);
        progressRepo.start(bookId, Times.now());
    }

    /** Chroma 集合名（数据库设计 §6：book_{book_id}） */
    public static String collectionName(String bookId) {
        return "book_" + bookId;
    }

    private boolean checkCancelled(String bookId) {
        return cancelFlags.contains(bookId);
    }

    private String currentStatus(boolean rebuild) {
        return rebuild ? "REBUILDING" : "BUILDING";
    }

    private void fail(String bookId, ErrorCode code, String message) {
        fail(bookId, String.valueOf(code.getCode()), message);
    }

    private void fail(String bookId, String code, String message) {
        bookRepo.markFailed(bookId, code, message, Times.now());
    }

    private void failQuietly(String bookId, ErrorCode code, String message) {
        try {
            fail(bookId, code, message);
        } catch (Exception ignored) {
            log.error("mark failed also failed bookId={}", bookId);
        }
    }

    private int defaultChunkSize() {
        return ragProps != null && ragProps.getChunkSize() > 0 ? ragProps.getChunkSize() : 600;
    }

    private int defaultOverlap() {
        return ragProps != null && ragProps.getOverlap() >= 0 ? ragProps.getOverlap() : 100;
    }

    private static String toJsonArray(List<Integer> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i));
        }
        return sb.append(']').toString();
    }

    @PreDestroy
    public void shutdown() {
        log.info("build task service shutting down, running={}", running);
    }
}
