package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.dto.eval.EvalRunRequest;
import com.readingnotes.service.EvalService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估接口（API 文档 §11）
 * 11.1 POST /eval/run           启动评估（异步，202 Accepted）
 * 11.2 GET  /eval/results       评估结果列表（分页）
 * 11.3 GET  /eval/results/{id}  单个评估报告（含失败项详情）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** 11.1 启动评估 — 从 eval_datasets 取题，逐条跑完整 RAG 链路，计算 7 项指标 */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Map<String, Object>> run(@Valid @RequestBody EvalRunRequest req) {
        return Result.ok(evalService.runEval(req));
    }

    /** 11.2 评估结果列表 — 精简字段分页 */
    @GetMapping("/results")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int page_size) {
        List<Map<String, Object>> items = evalService.listResults(page, page_size);
        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("page", page);
        data.put("page_size", page_size);
        return Result.ok(data);
    }

    /** 11.3 单个评估报告 — 含全部指标 + fail_items 详情 */
    @GetMapping("/results/{eval_run_id}")
    public Result<Map<String, Object>> detail(@PathVariable String eval_run_id) {
        return Result.ok(evalService.getResultDetail(eval_run_id));
    }
}
