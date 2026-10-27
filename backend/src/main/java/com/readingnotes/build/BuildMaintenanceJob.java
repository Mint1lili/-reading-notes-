package com.readingnotes.build;

import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Book;
import com.readingnotes.repository.BookRepository;
import com.readingnotes.repository.BuildProgressRepository;
import com.readingnotes.repository.ChapterRepository;
import com.readingnotes.repository.ChunkRepository;
import com.readingnotes.service.BookService;
import com.readingnotes.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建与回收站的守护任务。
 *
 * <h3>1. 启动自愈（{@link #resumeInterruptedBuilds}）</h3>
 * 进程被杀 / 断电后，{@code books.status} 可能停留在 BUILDING/REBUILDING。
 * 应用就绪后扫描这些书，按 {@code build_progress.last_completed_batch} 自动续跑，
 * 满足"构建中断可续、无需用户手动点重试"的体验要求（F2.5）。
 *
 * <h3>2. 回收站自动清理（{@link #cleanupTrash}）</h3>
 * F1.2：软删除进回收站，7 天后自动清理。每天 03:30 扫描 {@code purge_at <= now} 的书并永久删除。
 */
@Slf4j
@Component
@EnableScheduling
public class BuildMaintenanceJob {

    private final BookRepository bookRepo;
    private final ChapterRepository chapterRepo;
    private final ChunkRepository chunkRepo;
    private final BuildProgressRepository progressRepo;
    private final BuildTaskService buildTaskService;
    private final BookService bookService;

    public BuildMaintenanceJob(BookRepository bookRepo,
                               ChapterRepository chapterRepo,
                               ChunkRepository chunkRepo,
                               BuildProgressRepository progressRepo,
                               BuildTaskService buildTaskService,
                               BookService bookService) {
        this.bookRepo = bookRepo;
        this.chapterRepo = chapterRepo;
        this.chunkRepo = chunkRepo;
        this.progressRepo = progressRepo;
        this.buildTaskService = buildTaskService;
        this.bookService = bookService;
    }

    /**
     * 启动自愈：续跑被中断的构建。
     * <p>
     * 只对 BUILDING/REBUILDING 且 PDF 仍存在的书执行；PDF 已丢失的直接置 FAILED，
     * 避免任务空转。串行队列保证不会与用户新提交的构建并发。
     * <p>
     * <b>整个方法必须吞异常</b>：它在 {@code ApplicationReadyEvent} 回调里执行，
     * 此时 Web 容器已启动、应用已就绪。若数据库尚未就绪（容器编排时 MySQL 比应用慢、
     * 或本地忘开 MySQL）就让异常逸出，会打出一坨堆栈并干扰启动日志的可读性，
     * 而真正该做的是"下一轮再说"——这类书状态仍是 BUILDING，用户手动点重试即可。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedBuilds() {
        List<Book> building;
        try {
            building = new ArrayList<>(bookRepo.listAll("BUILDING", 1, 100));
            building.addAll(bookRepo.listAll("REBUILDING", 1, 100));
        } catch (Exception e) {
            log.warn("skip build self-heal, database not ready: {}", e.getMessage());
            return;
        }
        if (building.isEmpty()) {
            return;
        }
        log.info("found {} interrupted build(s), resuming from checkpoint", building.size());
        for (Book b : building) {
            try {
                // 统一传 rebuild=false：是否复用已落库切片由 BuildTaskService.runBuild 依据
                // build_progress 判断（canResume && 切片数 == 计划数），这里不重复推断。
                // 若当时重建还没跑完 cleanPreviousArtifacts，进度表里没有批次规划，
                // canResume 为 false，runBuild 会自然地重新解析+切片，不会误用旧的残留数据。
                buildTaskService.submit(b.getBookId(), null, null, false);
                log.info("resume build submitted bookId={} status={} progress={}",
                        b.getBookId(), b.getStatus(), b.getBuildProgress());
            } catch (Exception e) {
                log.error("resume build failed bookId={}", b.getBookId(), e);
                try {
                    bookRepo.markFailed(b.getBookId(),
                            String.valueOf(ErrorCode.BUILD_FAILED.getCode()),
                            "启动时恢复构建失败，请手动重试", Times.now());
                } catch (Exception ignored) {
                    // 数据库也挂了，等用户手动处理
                }
            }
        }
    }

    /**
     * 回收站自动清理：purge_at 已过期 → 永久删除。
     * cron 由 {@code build.trash-cleanup-cron} 配置（默认每天 03:30）。
     * <p>
     * 同样要吞住异常：{@code @Scheduled} 方法抛异常会被 Spring 记为 error，
     * 下一次触发仍会执行，但日志里反复堆栈不利于排查；单本书失败也不该影响其他书。
     */
    @Scheduled(cron = "${build.trash-cleanup-cron:0 30 3 * * ?}")
    public void cleanupTrash() {
        String now = Times.now();
        List<Book> expired;
        try {
            expired = bookRepo.listExpired(now);
        } catch (Exception e) {
            log.warn("trash cleanup skipped, query failed: {}", e.getMessage());
            return;
        }
        if (expired.isEmpty()) {
            log.debug("trash cleanup: nothing to purge");
            return;
        }
        log.info("trash cleanup start, expired={}", expired.size());
        int ok = 0, fail = 0;
        for (Book b : expired) {
            // 构建任务残留时先停掉，避免清理后任务还在写向量
            if (buildTaskService.isRunning(b.getBookId())) {
                buildTaskService.cancel(b.getBookId());
                buildTaskService.awaitStop(b.getBookId(), 10_000L);
            }
            try {
                bookService.purge(b.getBookId());
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("trash cleanup failed bookId={} {}", b.getBookId(), e.getMessage());
            }
        }
        log.info("trash cleanup done, purged={} failed={}", ok, fail);
    }

    /** 供测试/运维手动触发清理（不改 cron） */
    public void cleanupTrashNow() {
        cleanupTrash();
    }
}
