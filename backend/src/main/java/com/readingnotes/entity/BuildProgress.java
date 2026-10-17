package com.readingnotes.entity;

import lombok.Data;

/**
 * 构建进度与断点（数据库设计 §5.13 build_progress 表）
 * 每个 book 一行，断点续传的依据。
 */
@Data
public class BuildProgress {

    private String bookId;
    /** 最后完成的批次号（0 表示尚无完成批次；续跑从 last_completed_batch 开始） */
    private Integer lastCompletedBatch;
    private Integer totalBatches;
    private Integer chunkCountPlanned;
    private Integer chunkCountActual;
    /** JSON 数组，如 "[3,7]" */
    private String failedBatchesJson;
    private Double chapterRecognizedRate;
    private String buildStartedAt;
    private String lastUpdatedAt;
}
