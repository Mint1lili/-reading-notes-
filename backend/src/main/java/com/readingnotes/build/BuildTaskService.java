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
import java.io.IOException;
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
    private final BuildPreviewService previewService;
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
                            BuildPreviewService previewService,
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
        this.previewService = previewService;
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

            // ---------- 1. 解析 + 章节识别（10 → 20） ----------
            // 复用 BuildPreviewService 的解析缓存：用户先在预览页看过这本书的话，这里不再重复解析
            BuildPreviewService.ParsedBook parsed = previewService.parse(bookId, pdf);
            int totalPages = parsed.getTotalPages();
            if (checkCancelled(bookId)) {
                bookRepo.markCancelled(bookId, Times.now());
                return;
            }
            bookRepo.updateStage(bookId, currentStatus(rebuild), "RECOGNIZE", 20, Times.now());
            if (checkCancelled(bookId)) {
                bookRepo.markCancelled(bookId, Times.now());
                return;
            }
            bookRepo.updateStage(bookId, currentStatus(rebuild), "CHUNK", 30, Times.now());

            // ---------- 2. 切片（30） ----------
            // 断点续传：切片已落库且批次规划一致 → 直接复用，不重新解析
            int size = chunkSize != null ? chunkSize : defaultChunkSize();
            int ov = overlap != null ? overlap : defaultOverlap();
            boolean reuseChunks = !rebuild && snap.canResume
                    && snap.totalBatches != null && snap.planned != null
                    && snap.planned > 0 && chunkRepo.countByBook(bookId, null) == snap.planned;

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
                ChapterFlowBuilder.Flow flow = parsed.getFlow();
                chapterRate = flow.getRecognizedRate();
                // 章节率过低：记警告，构建继续（需求 F2.3 / 错误码 30003 是警告不是错误）
                if (flow.isHasStructure() && chapterRate < buildProps.getChapterRateWarnThreshold()) {
                    log.warn("chapter rate too low bookId={} rate={} — fallback to pure vector search",
                            bookId, chapterRate);
                }

                // 切片超硬上限时不再静默截断（会丢正文），先自动调粗粒度高一级再试；
                // 调到上限仍超 → 明确失败，让用户拆分上传，而不是交出一本"少了几章"的书。
                SemanticChunker.ChunkPlan plan = chunker.chunk(bookId, flow.getParagraphs(), size, ov);
                int adjusted = 0;
                while (plan.isTruncated() && size < buildProps.getChunkSizeMax()) {
                    int next = Math.min(buildProps.getChunkSizeMax(), (int) Math.round(size * 1.5));
                    if (next == size) {
                        break;
                    }
                    size = next;
                    ov = Math.min(ov, size / 3);
                    plan = chunker.chunk(bookId, flow.getParagraphs(), size, ov);
                    adjusted++;
                    log.warn("chunks exceeded hard limit, coarsen granularity bookId={} newSize={} attempt={}",
                            bookId, size, adjusted);
                }
                if (plan.isTruncated()) {
                    fail(bookId, ErrorCode.BUILD_FAILED,
                            "本书篇幅过大，超过单本 " + buildProps.getChunkHardLimit()
                                    + " 片上限。请拆分成多本上传，或联系管理员调高 build.chunk-hard-limit");
                    return;
                }
                if (adjusted > 0) {
                    log.info("chunk granularity auto-adjusted bookId={} from={} to={} attempts={}",
                            bookId, chunkSize != null ? chunkSize : defaultChunkSize(), size, adjusted);
                }

                persistChapters(bookId, flow, totalPages);
                persistChunks(bookId, plan);
                plannedChunks = plan.getChunks().size();
                totalBatches = plan.getTotalBatches();
                if (plannedChunks == 0) {
                    fail(bookId, ErrorCode.BUILD_FAILED, "切片结果为空，请检查 PDF 内容");
                    return;
                }
                if (plannedChunks > buildProps.getChunkSoftLimit()) {
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
                String message = "批次 " + outcome.failedBatches.size() + " 个失败（"
                        + outcome.failedBatches + "），可点击重试续跑";
                // 依赖类错误（key 无效 / 模型不存在 / 限流 / 维度不符 / Chroma 掉线）原样透出，
                // 否则用户只看到"批次失败"，不知道该去改什么配置。
                BusinessException last = outcome.lastError;
                if (last != null && last.getCode() != ErrorCode.BUILD_FAILED.getCode()) {
                    code = String.valueOf(last.getCode());
                    message = last.getMessage() + "（失败批次 " + outcome.failedBatches.size()
                            + " 个，修复后点击重试续跑）";
                }
                fail(bookId, code, message);
                return;
            }

            // ---------- 5. 入库完整性校验 + 收尾（100） ----------
            // Chroma 的 upsert 是逐批的，网络抖动/单批静默失败都可能让集合少几条；
            // 不校验就置 READY，问答时表现为"某几章永远检索不到"，且无从定位。
            long indexed = -1L;
            try {
                indexed = chroma.count(collectionName(bookId));
            } catch (Exception e) {
                log.warn("chroma count check skipped bookId={} {}", bookId, e.getMessage());
            }
            int expected = (int) chunkRepo.countByBook(bookId, null);
            if (indexed >= 0 && expected > 0 && indexed < expected) {
                log.error("ingest incomplete bookId={} expected={} actual={}", bookId, expected, indexed);
                // 必须重置切片状态，否则重试会跳过所有 DONE 批次，永远补不齐
                chunkRepo.resetEmbeddingStatus(bookId);
                progressRepo.resetResumePoint(bookId, 0, Times.now());
                fail(bookId, ErrorCode.BUILD_FAILED,
                        "知识库写入不完整（应有 " + expected + " 片，实际 " + indexed
                                + " 片），已保留切片，请点击重试补齐");
                return;
            }
            if (indexed > expected && expected > 0) {
                log.warn("chroma has more vectors than chunks bookId={} expected={} actual={}",
                        bookId, expected, indexed);
            }

            bookRepo.markReady(bookId, plannedChunks, chapterRate,
                    doubaoProps.getEmbeddingModel(), doubaoProps.getEmbeddingDim(),
                    totalPages, Times.now());
            log.info("==== build done bookId={} chunks={} batches={} elapsed={}ms ====",
                    bookId, plannedChunks, totalBatches, System.currentTimeMillis() - t0);

        } catch (BusinessException e) {
            log.warn("build business error bookId={} code={} msg={}", bookId, e.getCode(), e.getMessage());
            fail(bookId, String.valueOf(e.getCode()), e.getMessage());
        } catch (IOException e) {
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

            boolean[] rateLimited = new boolean[1];
            try {
                List<String> texts = batch.stream().map(Chunk::getContent).toList();
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
                // Chroma 整体不可用 / key 无效 / 模型不存在 / 持续限流 / 维度不符 → 后续批次必然全失败，
                // 直接中止并保留断点，用户修好配置后点「重试」即可从失败批次续跑。
                if (isFatalDependencyError(e.getCode())) {
                    log.error("fatal dependency error, abort remaining batches bookId={} code={}",
                            bookId, e.getCode());
                    break;
                }
            }

            // 本批触发过 429：主动冷却再继续，避免把剩余批次全部撞成失败
            if (rateLimited[0]) {
                if (checkCancelled(bookId)) {
                    out.cancelled = true;
                    return out;
                }
                long cooldown = Math.max(0, buildProps.getRateLimitCooldownMs());
                if (cooldown > 0) {
                    log.warn("上一批触发限流，冷却 {}ms 后继续 bookId={} batch={}", cooldown, bookId, b);
                    try {
                        Thread.sleep(cooldown);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        out.cancelled = true;
                        return out;
                    }
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

    /** 断点快照：能否续跑、总批次、计划切片数、已完成批次、章节识别率 */
    private static class BuildProgressSnapshot {
        boolean canResume;
        Integer totalBatches;
        Integer planned;
        Integer lastCompletedBatch = 0;
        Double chapterRate;
    }

    /**
     * 读取断点；无记录则建一条空的起始记录。
     * <p>
     * 只有"已有批次规划且断点未跑完"才允许续跑：首次构建或已全部完成的，都应走完整流程，
     * 否则会跳过切片阶段直接进 EMBED，导致 chunk 表为空。
     */
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

    /**
     * 是否已收到取消请求。
     * <p>
     * 用内存标记而不是查库：worker 在每个批次开头高频调用，查库会成为热点；
     * 代价是重启后标记丢失，需依赖 build_progress 的状态判断兜底。
     */
    private boolean checkCancelled(String bookId) {
        return cancelFlags.contains(bookId);
    }

    /**
     * 是否为「继续跑也没用」的依赖级错误：配置错误或下游整体不可用。
     * 命中后中止剩余批次，保留断点等用户修复后重试。
     */
    private boolean isFatalDependencyError(int code) {
        return code == ErrorCode.CHROMA_DOWN.getCode()
                || code == ErrorCode.DIM_MISMATCH.getCode()
                || code == ErrorCode.DOUBAO_DOWN.getCode()
                || code == ErrorCode.DOUBAO_AUTH_FAILED.getCode()
                || code == ErrorCode.DOUBAO_MODEL_NOT_FOUND.getCode()
                || code == ErrorCode.DOUBAO_MODEL_NOT_OPEN.getCode()
                || code == ErrorCode.DOUBAO_RATE_LIMITED.getCode();
    }

    /** 构建中的状态值：重建走 REBUILDING，首次构建走 BUILDING（前端据此区分文案） */
    private String currentStatus(boolean rebuild) {
        return rebuild ? "REBUILDING" : "BUILDING";
    }

    /** 标记失败（错误码枚举重载） */
    private void fail(String bookId, ErrorCode code, String message) {
        fail(bookId, String.valueOf(code.getCode()), message);
    }

    /** 标记失败：写 status=FAILED + error_code/error_message，供前端展示与重试判断 */
    private void fail(String bookId, String code, String message) {
        bookRepo.markFailed(bookId, code, message, Times.now());
    }

    /**
     * 标记失败但吞掉异常。
     * <p>
     * 用于 catch 块内：此时数据库可能也不可用，若再抛异常会覆盖原始错误，
     * 让用户看到"标记失败失败"而不是真正的失败原因。
     */
    private void failQuietly(String bookId, ErrorCode code, String message) {
        try {
            fail(bookId, code, message);
        } catch (Exception ignored) {
            log.error("mark failed also failed bookId={}", bookId);
        }
    }

    /** 默认切片字数：未配置 rag.* 时回落到 600（与需求 F2.3 的中粒度一致） */
    private int defaultChunkSize() {
        return ragProps != null && ragProps.getChunkSize() > 0 ? ragProps.getChunkSize() : 600;
    }

    /** 默认重叠字数：未配置时回落到 100 */
    private int defaultOverlap() {
        return ragProps != null && ragProps.getOverlap() >= 0 ? ragProps.getOverlap() : 100;
    }

    /** 手写 JSON 数组（避免为一个字段引入序列化依赖），如 [3,7] */
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
