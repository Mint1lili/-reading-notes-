package com.readingnotes.controller;

import com.readingnotes.ai.SummaryService;
import com.readingnotes.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全书摘要接口（F3.1，API 文档 §7.1/7.2/7.3）
 *
 *  - POST /api/v1/ai/summary                    提交摘要任务（202 异步，map-reduce）
 *  - GET  /api/v1/ai/summary/{book_id}          查询摘要（version=latest|N）
 *  - POST /api/v1/ai/summary/{book_id}/regenerate  重新生成（新版本，不删除旧版本）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/summary")
public class AiSummaryController {

    private final SummaryService summaryService;

    public AiSummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** 提交摘要任务：body={book_id, temperature, detail_level} → 202 信封 {task_id,...} */
    @PostMapping
    public ResponseEntity<Result<Map<String, Object>>> submit(@RequestBody Map<String, Object> body) {
        String bookId = body.get("book_id") == null ? null : String.valueOf(body.get("book_id"));
        Double temperature = body.get("temperature") == null ? null
                : ((Number) body.get("temperature")).doubleValue();
        String detailLevel = body.get("detail_level") == null ? null : String.valueOf(body.get("detail_level"));
        String taskId = summaryService.submitSummary(bookId, temperature, detailLevel);
        summaryService.runSummary(taskId, bookId.trim(), temperature == null ? 0.3 : temperature, detailLevel);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.ok(summaryService.taskResult(taskId, bookId.trim())));
    }

    /** 查询摘要：GET /summary/{book_id}?version=latest|N → 信封 {book_id, versions, content} */
    @GetMapping("/{book_id}")
    public Result<Map<String, Object>> get(@PathVariable("book_id") String bookId,
                                   @RequestParam(value = "version", required = false) String version) {
        return Result.ok(summaryService.getSummary(bookId, version));
    }

    /** 重新生成摘要：body={temperature, detail_level} → 202 信封 {task_id,...} */
    @PostMapping("/{book_id}/regenerate")
    public ResponseEntity<Result<Map<String, Object>>> regenerate(@PathVariable("book_id") String bookId,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        Double temperature = body == null || body.get("temperature") == null ? null
                : ((Number) body.get("temperature")).doubleValue();
        String detailLevel = body == null || body.get("detail_level") == null ? null
                : String.valueOf(body.get("detail_level"));
        String taskId = summaryService.submitSummary(bookId, temperature, detailLevel);
        summaryService.runSummary(taskId, bookId.trim(), temperature == null ? 0.3 : temperature, detailLevel);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.ok(summaryService.taskResult(taskId, bookId.trim())));
    }
}
