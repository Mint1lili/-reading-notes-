package com.readingnotes.service;

import com.readingnotes.dto.eval.EvalRunRequest;
import com.readingnotes.repository.EvalRepository;

import java.util.List;
import java.util.Map;

/**
 * RAG 评估服务（接口 11）
 */
public interface EvalService {

    /**
     * 启动一次评估（异步执行，立即返回 RUNNING 状态）
     *
     * @param req 评估请求（数据集版本、类别、抽样数）
     * @return { eval_run_id, status, total_items }
     */
    Map<String, Object> runEval(EvalRunRequest req);

    /**
     * 分页查询评估结果列表
     */
    List<Map<String, Object>> listResults(int page, int pageSize);

    /**
     * 查询单个评估报告（含失败项详情）
     */
    Map<String, Object> getResultDetail(String evalRunId);
}
