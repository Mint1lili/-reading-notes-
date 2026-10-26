package com.readingnotes.entity;

import lombok.Data;

/**
 * 构建进度与断点（数据库设计 §5.13 build_progress 表）。
 * <p>
 * 每个 book 一行，是断点续传的唯一依据：进程崩溃重启后，
 * 从 {@code lastCompletedBatch + 1} 继续，已成功的批次不重复调用 Embedding。
 */
@Data
public class BuildProgress {

    private String bookId;

    /** 最后完成的批次号（0 表示尚无完成批次；续跑从 last_completed_batch 开始） */
    private Integer lastCompletedBatch;

    /** 本次构建规划的总批次数，切片阶段即确定，构建中不变 */
    private Integer totalBatches;

    /** 计划入库的切片数（切片阶段算出） */
    private Integer chunkCountPlanned;

    /** 实际已入库的切片数，用于构建结束时的完整性校验 */
    private Integer chunkCountActual;

    /** JSON 数组，如 "[3,7]"；记录失败批次，重试时只补跑这些批次 */
    private String failedBatchesJson;

    private Double chapterRecognizedRate;

    /** 本次构建开始时间；用于识别"僵尸构建"（进程已死但状态仍为 BUILDING） */
    private String buildStartedAt;

    /** 进度最后更新时间，前端据此判断任务是否卡死 */
    private String lastUpdatedAt;
}
