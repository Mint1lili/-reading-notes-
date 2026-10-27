package com.readingnotes.controller;

import com.readingnotes.ai.SummaryService;
import com.readingnotes.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 任务状态接口（API 文档 §7.12，通用轮询）
 *
 *  GET /api/v1/ai/tasks/{task_id} → 信封
 *  → data: {task_id, task_type, status, retry_count, progress_hint, params_json,
 *     result_json, error_message, created_at, started_at, finished_at, trace_id}
 *
 * 摘要/导图类任务通过子任务统计 progress_hint（"已生成 X/N 章小结"），
 * 问答类任务（SSE 已完成即 COMPLETED）返回基础字段。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/tasks")
public class AiTaskController {

    private final SummaryService summaryService;

    public AiTaskController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/{task_id}")
    public Result<Map<String, Object>> getTask(@PathVariable("task_id") String taskId) {
        return Result.ok(summaryService.getTask(taskId));
    }
}
