package com.readingnotes.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 任务维护：摘要/导图任务运行在 JVM 内（@Async aiExecutor），
 * 服务重启会中断所有在途线程，导致 ai_tasks 里 QUEUED/RUNNING 任务永远卡死
 * （前端轮询 5 分钟后只能报"生成超时"）。
 *
 * 本组件做两件事（与构建侧 BuildMaintenanceJob 对应，但针对 SUMMARY/MINDMAP）：
 *  1. 启动扫描（ApplicationRunner）：服务重启后，把重启前遗留的
 *     QUEUED/RUNNING 任务标记 FAILED，前端轮询到 FAILED 会提示重新生成；
 *  2. 超时回收（@Scheduled）：RUNNING 超过 10 分钟未完成的任务视为卡死，
 *     自动标记 FAILED（正常摘要 map-reduce 约 1~3 分钟）。
 */
@Slf4j
@Component
public class AiTaskMaintenanceJob implements ApplicationRunner {

    private static final String[] TASK_TYPES = {"SUMMARY", "MINDMAP"};
    /** RUNNING 超过该时长视为卡死（秒） */
    private static final long STALE_SECONDS = 10 * 60L;

    private final JdbcTemplate jdbc;

    public AiTaskMaintenanceJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 重启前 60 秒以内开始的任务视为"可能仍在收尾"，不误杀；更早的均为重启中断
        int n = jdbc.update("""
                UPDATE ai_tasks
                   SET status='FAILED',
                       error_message='服务重启中断，任务未完成，请重新生成',
                       finished_at=NOW()
                 WHERE task_type IN ('SUMMARY','MINDMAP')
                   AND status IN ('QUEUED','RUNNING')
                   AND (started_at IS NULL OR started_at < DATE_SUB(NOW(), INTERVAL 60 SECOND))
                """);
        if (n > 0) {
            log.warn("AiTaskMaintenance: marked {} stale AI tasks FAILED after restart", n);
        }
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void reapStaleTasks() {
        int n = jdbc.update("""
                UPDATE ai_tasks
                   SET status='FAILED',
                       error_message='任务执行超时（超过 10 分钟），已自动终止，请重新生成',
                       finished_at=NOW()
                 WHERE task_type IN ('SUMMARY','MINDMAP')
                   AND status='RUNNING'
                   AND started_at IS NOT NULL
                   AND started_at < DATE_SUB(NOW(), INTERVAL %d SECOND)
                """.formatted(STALE_SECONDS));
        if (n > 0) {
            log.warn("AiTaskMaintenance: reaped {} stuck AI tasks (timeout > {}s)", n, STALE_SECONDS);
        }
    }
}
