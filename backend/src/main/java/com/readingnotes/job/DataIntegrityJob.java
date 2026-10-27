package com.readingnotes.job;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.config.AppProperties;
import com.readingnotes.entity.Book;
import com.readingnotes.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据完整性维护（深层缺陷⑤⑥）：
 * <p>
 * ⑤ READY 状态一致性校验：books.status=READY 表示"可阅读/可问答"，但历史上存在
 *    PDF 原文件丢失（20010）、Chroma 无向量集合（QA 永远 40002）等脏状态——
 *    页面显示可读却一问就报错。本 Job 定时把"既无原文件又无向量"的书降级 FAILED
 *    （提示重新上传），避免脏 READY 欺骗用户。
 * <p>
 * ⑥ 跨存储一致性巡检：MySQL(chunk_count / chunks 表) 与 Chroma(count) 是两套存储，
 *    写入非原子（先 MySQL 后 Chroma），失败即永久不一致。本 Job 周期对比三处数量，
 *    不一致时告警日志（不自动改，防止误伤；供人工/重构建修复）。
 * <p>
 * 校验原则：向量存在即可问答 → 保持 READY（问答不依赖 PDF）；两者皆缺 → FAILED。
 */
@Slf4j
@Component
public class DataIntegrityJob implements ApplicationRunner {

    private final BookRepository bookRepo;
    private final ChromaClient chroma;
    private final JdbcTemplate jdbc;
    private final AppProperties appProps;

    public DataIntegrityJob(BookRepository bookRepo, ChromaClient chroma,
                            JdbcTemplate jdbc, AppProperties appProps) {
        this.bookRepo = bookRepo;
        this.chroma = chroma;
        this.jdbc = jdbc;
        this.appProps = appProps;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动后延迟执行（等 Web/Chroma 就绪），失败仅告警不阻断启动
        try {
            Thread.sleep(15_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            checkOnce();
        } catch (Exception e) {
            log.warn("DataIntegrityJob startup check skipped: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${build.integrity-check-cron:0 0 */6 * * ?}")
    public void checkOnce() {
        List<Book> ready;
        try {
            ready = bookRepo.listAll("READY", 1, 500);
        } catch (Exception e) {
            log.warn("DataIntegrityJob: database not ready, skip: {}", e.getMessage());
            return;
        }
        if (ready.isEmpty()) {
            return;
        }
        List<String> degraded = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Book b : ready) {
            try {
                String bookId = b.getBookId();
                boolean pdfExists = pdfExists(bookId);
                Long chunkRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chunks WHERE book_id = ?", Long.class, bookId);
                long chromaCount = chromaCountSafe(bookId);
                long mysqlCount = b.getChunkCount() == null ? 0 : b.getChunkCount();

                // ⑥ 跨存储巡检：三处数量不一致 → 告警
                if (mysqlCount != (chunkRows == null ? 0 : chunkRows)
                        || mysqlCount != chromaCount) {
                    mismatches.add(String.format("%s(MySQL=%d/chunks=%d/Chroma=%d)",
                            bookId, mysqlCount, chunkRows == null ? 0 : chunkRows, chromaCount));
                }

                // ⑤ READY 校验：原文件与向量都缺失 → 降级 FAILED（提示重新上传）
                if (!pdfExists && chromaCount <= 0) {
                    jdbc.update("""
                            UPDATE books SET status='FAILED', error_code='20010',
                                   error_message='原书文件与知识库均缺失，请重新上传',
                                   updated_at=NOW()
                             WHERE book_id = ?
                            """, bookId);
                    degraded.add(bookId);
                    log.warn("DataIntegrityJob: degraded book {} (READY→FAILED, no pdf & no vectors)", bookId);
                } else if (!pdfExists) {
                    // PDF 丢但向量在：问答仍可用，仅记录（不降级，避免误伤可读书）
                    log.warn("DataIntegrityJob: book {} pdf missing but vectors present (qa usable)", bookId);
                }
            } catch (Exception e) {
                log.warn("DataIntegrityJob: check book {} failed: {}", b.getBookId(), e.getMessage());
            }
        }
        if (!mismatches.isEmpty()) {
            log.warn("DataIntegrityJob: cross-storage mismatch {} book(s): {}", mismatches.size(), mismatches);
        }
        if (!degraded.isEmpty()) {
            log.warn("DataIntegrityJob: degraded {} stale READY book(s) to FAILED: {}", degraded.size(), degraded);
        }
    }

    private boolean pdfExists(String bookId) {
        try {
            Path p = Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize()
                    .resolve(bookId + ".pdf");
            return Files.exists(p);
        } catch (Exception e) {
            return false;
        }
    }

    private long chromaCountSafe(String bookId) {
        try {
            return chroma.count("book_" + bookId);
        } catch (Exception e) {
            // Chroma 整体不可用：不计入降级（可能是服务停机），仅返回 0 且不抛
            return 0;
        }
    }
}
