package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 构建任务配置（application.yml 的 build.*，对应需求 F2 / §6.5）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "build")
public class BuildProperties {

    /** 构建 worker 线程数：串行 1，避免 Embedding 并发触发 429（需求 §6.5） */
    private int workerThreads = 1;

    /** 单书切片软上限：超出不阻塞构建，只在预览页红色警告（F2.2） */
    private int chunkSoftLimit = 5000;

    /** 单书切片硬上限：超出即截断，防止内存/额度被打爆 */
    private int chunkHardLimit = 8000;

    /** 切片粒度允许的最小/最大字数（F2.3：粗 1000 / 中 600 / 细 300） */
    private int chunkSizeMin = 200;
    private int chunkSizeMax = 1200;

    /** 单本 PDF 页数上限：超过直接拒绝，避免解析耗时不可控（F2.1） */
    private int maxPages = 2000;

    /** 分片上传单片大小（字节），默认 5MB（F1.1 断点续传） */
    private long uploadChunkSize = 5L * 1024 * 1024;

    /** 分片上传会话有效期（小时），超时清理临时分片 */
    private int uploadSessionTtlHours = 24;

    /** Embedding 每批条数（F2.4）。注意：这是「DB 批次」粒度，单次 HTTP 请求条数由 doubao.batch-size 控制 */
    private int embeddingBatchSize = 16;

    /** 某批触发过 429 后，下一批之前的冷却时间（毫秒）：方舟配额恢复常需数十秒，主动降速比硬冲更稳 */
    private long rateLimitCooldownMs = 2000;

    /** 章节识别率低于此值时提示"未识别到章节结构"（F2.3） */
    private double chapterRateWarnThreshold = 0.5;

    /** 回收站保留天数（F1.2：7 天后自动清理） */
    private int trashRetainDays = 7;

    /** 回收站清理任务 cron（默认每天 03:30） */
    private String trashCleanupCron = "0 30 3 * * ?";
}
