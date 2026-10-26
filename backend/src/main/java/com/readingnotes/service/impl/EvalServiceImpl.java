package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.dto.eval.EvalRunRequest;
import com.readingnotes.dto.eval.FailItemDTO;
import com.readingnotes.repository.EvalRepository;
import com.readingnotes.service.EvalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估服务实现（接口 11）
 */
@Slf4j
@Service
public class EvalServiceImpl implements EvalService {

    private final EvalRepository evalRepo;
    private final EvalTaskRunner evalTaskRunner;
    private final ObjectMapper objectMapper;

    public EvalServiceImpl(EvalRepository evalRepo, EvalTaskRunner evalTaskRunner,
                           ObjectMapper objectMapper) {
        this.evalRepo = evalRepo;
        this.evalTaskRunner = evalTaskRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> runEval(EvalRunRequest req) {
        // 计算本次评估的总题数（用于立即返回给前端）
        int total = evalRepo.countItems(req.getCategories());
        int sampleSize = req.getSampleSize() == null ? 0 : req.getSampleSize();
        if (sampleSize > 0) {
            total = Math.min(total, sampleSize);
        }

        String evalRunId = "eval_" + System.currentTimeMillis();
        String startedAt = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        // 先写一条 RUNNING 记录（finished_at 为空表示进行中）
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = evalRunId;
        r.startedAt = startedAt;
        r.finishedAt = null;
        r.datasetVersion = req.getDatasetVersion();
        r.totalItems = total;
        evalRepo.insertRunning(r);

        // 异步执行完整 RAG 评估链路
        evalTaskRunner.execute(evalRunId, req.getDatasetVersion(), req.getCategories(), sampleSize);

        Map<String, Object> result = new HashMap<>();
        result.put("eval_run_id", evalRunId);
        result.put("status", "RUNNING");
        result.put("total_items", total);
        return result;
    }

    @Override
    public List<Map<String, Object>> listResults(int page, int pageSize) {
        List<EvalRepository.EvalResult> rows = evalRepo.listByPage(page, pageSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvalRepository.EvalResult r : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("eval_run_id", r.evalRunId);
            item.put("started_at", r.startedAt);
            item.put("finished_at", r.finishedAt);
            item.put("dataset_version", r.datasetVersion);
            item.put("total_items", r.totalItems);
            item.put("pass_items", r.passItems);
            item.put("recall_at_5", round4(r.recallAt5));
            item.put("recall_at_10", round4(r.recallAt10));
            item.put("answer_accuracy", round4(r.answerAccuracy));
            item.put("refusal_accuracy", round4(r.refusalAccuracy));
            item.put("citation_accuracy", round4(r.citationAccuracy));
            item.put("avg_first_token_ms", r.avgFirstTokenMs);
            item.put("avg_full_answer_ms", r.avgFullAnswerMs);
            item.put("status", r.finishedAt == null ? "RUNNING" : "FINISHED");
            items.add(item);
        }
        return items;
    }

    @Override
    public Map<String, Object> getResultDetail(String evalRunId) {
        EvalRepository.EvalResult r = evalRepo.findById(evalRunId);
        if (r == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评估不存在: " + evalRunId);
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("eval_run_id", r.evalRunId);
        detail.put("dataset_version", r.datasetVersion);
        detail.put("started_at", r.startedAt);
        detail.put("finished_at", r.finishedAt);
        detail.put("total_items", r.totalItems);
        detail.put("pass_items", r.passItems);
        detail.put("recall_at_5", round4(r.recallAt5));
        detail.put("recall_at_10", round4(r.recallAt10));
        detail.put("answer_accuracy", round4(r.answerAccuracy));
        detail.put("refusal_accuracy", round4(r.refusalAccuracy));
        detail.put("citation_accuracy", round4(r.citationAccuracy));
        detail.put("avg_first_token_ms", r.avgFirstTokenMs);
        detail.put("avg_full_answer_ms", r.avgFullAnswerMs);
        detail.put("report_path", r.reportPath);

        // 解析 fail_items_json
        List<FailItemDTO> failItems = new ArrayList<>();
        if (r.failItemsJson != null && !r.failItemsJson.isBlank()) {
            try {
                failItems = objectMapper.readValue(r.failItemsJson,
                        new TypeReference<List<FailItemDTO>>() {});
            } catch (Exception e) {
                log.warn("fail_items_json parse failed eval_run_id={}", evalRunId, e);
            }
        }
        detail.put("fail_items", failItems);
        detail.put("status", r.finishedAt == null ? "RUNNING" : "FINISHED");

        return detail;
    }

    /** 保留 4 位小数 */
    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
