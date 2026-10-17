package com.readingnotes.service.impl;

import com.readingnotes.build.BuildTaskService;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.BuildProperties;
import com.readingnotes.entity.Book;
import com.readingnotes.entity.BuildProgress;
import com.readingnotes.entity.Chapter;
import com.readingnotes.entity.Chunk;
import com.readingnotes.repository.BookRepository;
import com.readingnotes.repository.BuildProgressRepository;
import com.readingnotes.repository.ChapterRepository;
import com.readingnotes.repository.ChunkRepository;
import com.readingnotes.service.BuildService;
import com.readingnotes.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库构建查询/控制实现（F2.5 进度与断点续传）
 */
@Slf4j
@Service
public class BuildServiceImpl implements BuildService {

    private final BookRepository bookRepo;
    private final ChapterRepository chapterRepo;
    private final ChunkRepository chunkRepo;
    private final BuildProgressRepository progressRepo;
    private final BuildTaskService buildTaskService;
    private final BuildProperties buildProps;

    public BuildServiceImpl(BookRepository bookRepo,
                            ChapterRepository chapterRepo,
                            ChunkRepository chunkRepo,
                            BuildProgressRepository progressRepo,
                            BuildTaskService buildTaskService,
                            BuildProperties buildProps) {
        this.bookRepo = bookRepo;
        this.chapterRepo = chapterRepo;
        this.chunkRepo = chunkRepo;
        this.progressRepo = progressRepo;
        this.buildTaskService = buildTaskService;
        this.buildProps = buildProps;
    }

    // =========================================================================
    // 6.1 构建状态
    // =========================================================================

    @Override
    public Map<String, Object> status(String bookId) {
        Book book = bookRepo.findById(bookId);
        BuildProgress p = progressRepo.findOrNull(bookId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", book.getBookId());
        data.put("title", book.getTitle());
        data.put("status", book.getStatus());
        data.put("build_sub_stage", book.getBuildSubStage());
        data.put("build_progress", book.getBuildProgress());
        data.put("error_code", book.getErrorCode());
        data.put("error_message", book.getErrorMessage());
        data.put("chunk_count_planned", p == null ? null : p.getChunkCountPlanned());
        data.put("chunk_count_actual", p == null ? null : p.getChunkCountActual());
        data.put("total_batches", p == null ? null : p.getTotalBatches());
        data.put("last_completed_batch", p == null ? 0 : p.getLastCompletedBatch());
        data.put("failed_batches", parseFailedBatches(p == null ? null : p.getFailedBatchesJson()));
        data.put("chapter_recognized_rate", book.getChapterRecognizedRate() != null
                ? book.getChapterRecognizedRate()
                : (p == null ? null : p.getChapterRecognizedRate()));
        data.put("building", buildTaskService.isRunning(bookId));

        // 构建前/构建中的校验提示（F2.3）
        List<String> warnings = new ArrayList<>();
        Double rate = book.getChapterRecognizedRate() != null
                ? book.getChapterRecognizedRate()
                : (p == null ? null : p.getChapterRecognizedRate());
        if (rate != null && rate < buildProps.getChapterRateWarnThreshold()) {
            warnings.add("本书未识别到章节结构（识别率 " + Math.round(rate * 100)
                    + "%），问答将使用纯向量检索");
        }
        Integer planned = p == null ? null : p.getChunkCountPlanned();
        if (planned != null && planned > buildProps.getChunkSoftLimit()) {
            warnings.add("切片数 " + planned + " 超过 " + buildProps.getChunkSoftLimit()
                    + " 片软上限，建议切换到「粗」粒度（1000 字）或拆书上载");
        }
        data.put("warnings", warnings);
        return data;
    }

    // =========================================================================
    // 6.2 重试
    // =========================================================================

    @Override
    public Map<String, Object> retry(String bookId, Integer fromBatch) {
        Book book = bookRepo.findActive(bookId);
        if (buildTaskService.isRunning(bookId)) {
            throw new BusinessException(ErrorCode.BOOK_BUILDING, "构建任务正在进行中，请等待完成或先取消");
        }
        // 只有 FAILED / CANCELLED 才需要重试（30005）
        if (!"FAILED".equals(book.getStatus()) && !"CANCELLED".equals(book.getStatus())) {
            throw new BusinessException(ErrorCode.BUILD_TASK_DONE);
        }
        BuildProgress p = progressRepo.findOrNull(bookId);
        if (p == null || p.getTotalBatches() == null) {
            // 切片阶段都没跑完，没有可续跑的批次 → 从头构建
            log.info("retry without chunk plan, rebuild from scratch bookId={}", bookId);
            bookRepo.updateStage(bookId, "BUILDING", "PARSE", 10, Times.now());
            progressRepo.start(bookId, Times.now());
            buildTaskService.submit(bookId, null, null, false);
            return status(bookId);
        }

        // from_batch 显式指定则覆盖断点（需求：可单批次重试）
        if (fromBatch != null && fromBatch >= 0) {
            progressRepo.saveFailedBatches(bookId, null, Times.now());
            // 把断点回退到指定批次：直接更新 last_completed_batch
            resetResumePoint(bookId, fromBatch);
        }

        // 状态置回 BUILDING（仍是同一批切片，不重建）
        bookRepo.updateStage(bookId, "BUILDING", "EMBED", book.getBuildProgress() == null
                ? 30 : book.getBuildProgress(), Times.now());
        buildTaskService.submit(bookId, null, null, false);
        log.info("retry submitted bookId={} fromBatch={}", bookId,
                fromBatch != null ? fromBatch : p.getLastCompletedBatch());
        return status(bookId);
    }

    /** 把断点回退到指定批次（对应 build_progress.last_completed_batch = fromBatch） */
    private void resetResumePoint(String bookId, int fromBatch) {
        // 用 saveFailedBatches 之外的专用更新：直接写 last_completed_batch
        // 这里复用 markBatchCompleted 不适用（它是 GREATEST 语义），故走 SQL
        progressRepo.resetResumePoint(bookId, fromBatch, Times.now());
    }

    // =========================================================================
    // 6.3 取消
    // =========================================================================

    @Override
    public Map<String, Object> cancel(String bookId) {
        Book book = bookRepo.findActive(bookId);
        String status = book.getStatus();
        if (!"BUILDING".equals(status) && !"REBUILDING".equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前状态无法取消构建：" + status);
        }
        buildTaskService.cancel(bookId);
        // 已完成的批次保留，下次 retry 可续跑（API 文档 §6.3）
        bookRepo.markCancelled(bookId, Times.now());
        log.info("build cancelled by user bookId={}", bookId);
        return status(bookId);
    }

    // =========================================================================
    // 6.4 切片预览
    // =========================================================================

    @Override
    public Map<String, Object> chunks(String bookId, int page, int pageSize, String chapter) {
        Book book = bookRepo.findActive(bookId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<Chunk> list = chunkRepo.pageByBook(bookId, chapter, safePage, safeSize);
        long total = chunkRepo.countByBook(bookId, chapter);

        List<Map<String, Object>> items = new ArrayList<>(list.size());
        for (Chunk c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunk_id", c.getChunkId());
            m.put("content", c.getContent());
            m.put("page", c.getPage());
            m.put("chapter_path", c.getChapterPath());
            m.put("chapter_title", c.getChapterTitle());
            m.put("position", c.getPosition());
            m.put("word_count", c.getWordCount());
            m.put("embedding_status", c.getEmbeddingStatus());
            m.put("batch_id", c.getEmbeddingBatchId());
            items.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", bookId);
        data.put("status", book.getStatus());
        data.put("total", total);
        data.put("page", safePage);
        data.put("page_size", safeSize);
        data.put("items", items);
        return data;
    }

    // =========================================================================
    // 6.5 章节结构
    // =========================================================================

    @Override
    public Map<String, Object> chapters(String bookId) {
        Book book = bookRepo.findActive(bookId);
        List<Chapter> chapters = chapterRepo.listByBook(bookId);
        List<Map<String, Object>> items = new ArrayList<>(chapters.size());
        for (Chapter c : chapters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapter_id", c.getChapterId());
            m.put("chapter_path", c.getChapterPath());
            m.put("title", c.getTitle());
            m.put("level", c.getLevel());
            m.put("start_page", c.getStartPage());
            m.put("end_page", c.getEndPage());
            m.put("word_count", c.getWordCount());
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", bookId);
        data.put("chapter_recognized_rate", book.getChapterRecognizedRate());
        data.put("chapters", items);
        data.put("structure_recognized", book.getChapterRecognizedRate() != null
                && book.getChapterRecognizedRate() > 0);
        return data;
    }

    // =========================================================================
    // 构建前预览统计（F2.3：切片数 / 字数分布 / 章节识别率）
    // =========================================================================

    @Override
    public Map<String, Object> previewStats(Book book) {
        String bookId = book.getBookId();
        Map<String, Object> data = new LinkedHashMap<>();
        long total = chunkRepo.countByBook(bookId, null);
        int[] stats = chunkRepo.wordCountStats(bookId);
        long chapterCount = chapterRepo.listByBook(bookId).size();

        data.put("book_id", bookId);
        data.put("status", book.getStatus());
        data.put("chunk_count", total);
        data.put("chapter_count", chapterCount);
        data.put("chapter_recognized_rate", book.getChapterRecognizedRate());
        data.put("min_word_count", stats[0]);
        data.put("max_word_count", stats[1]);
        data.put("avg_word_count", stats[2]);
        data.put("embedding_done", chunkRepo.countDone(bookId));
        data.put("soft_limit", buildProps.getChunkSoftLimit());
        data.put("over_soft_limit", total > buildProps.getChunkSoftLimit());

        List<String> warnings = new ArrayList<>();
        if (book.getChapterRecognizedRate() != null
                && book.getChapterRecognizedRate() < buildProps.getChapterRateWarnThreshold()) {
            warnings.add("本书未识别到章节结构，问答将使用纯向量检索");
        }
        if (total > buildProps.getChunkSoftLimit()) {
            warnings.add("切片数超过软上限，建议切换到「粗」粒度或拆书上载");
        }
        data.put("warnings", warnings);
        return data;
    }

    private List<Integer> parseFailedBatches(String json) {
        List<Integer> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        for (String s : json.replaceAll("[\\[\\]\\s]", "").split(",")) {
            if (!s.isEmpty()) {
                try {
                    out.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    // 脏数据忽略
                }
            }
        }
        return out;
    }
}
