package com.readingnotes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.dto.eval.EvalRunRequest;
import com.readingnotes.dto.eval.FailItemDTO;
import com.readingnotes.repository.EvalRepository;
import com.readingnotes.service.impl.EvalServiceImpl;
import com.readingnotes.service.impl.EvalTaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RAG 评估服务（接口 11）单元测试
 * <p>
 * 覆盖：runEval 启动评估、listResults 分页列表、getResultDetail 详情 + fail_items 解析
 */
class EvalServiceImplTest {

    private EvalRepository evalRepo;
    private EvalTaskRunner evalTaskRunner;
    private ObjectMapper objectMapper;

    private EvalServiceImpl service;

    @BeforeEach
    void setUp() {
        evalRepo = mock(EvalRepository.class);
        evalTaskRunner = mock(EvalTaskRunner.class);
        objectMapper = new ObjectMapper();

        service = new EvalServiceImpl(evalRepo, evalTaskRunner, objectMapper);
    }

    // ====================================================================
    // 11.1 启动评估 runEval
    // ====================================================================

    @Test
    void runEval_returnsRunningStatusAndTotalItems() {
        EvalRunRequest req = new EvalRunRequest();
        req.setDatasetVersion("v1.0");
        req.setCategories(List.of("book_qa_simple", "book_qa_located"));
        req.setSampleSize(null);

        when(evalRepo.countItems(anyList())).thenReturn(50);

        Map<String, Object> result = service.runEval(req);

        assertEquals("RUNNING", result.get("status"));
        assertEquals(50, result.get("total_items"));
        assertNotNull(result.get("eval_run_id"));
        assertTrue(((String) result.get("eval_run_id")).startsWith("eval_"));
        verify(evalRepo).insertRunning(any(EvalRepository.EvalResult.class));
        verify(evalTaskRunner).execute(anyString(), eq("v1.0"), anyList(), eq(0));
    }

    @Test
    void runEval_withSampleSize_takesMinOfTotalAndSample() {
        EvalRunRequest req = new EvalRunRequest();
        req.setDatasetVersion("v1.0");
        req.setSampleSize(10);

        // 数据库有 50 题，但只抽样 10
        when(evalRepo.countItems(any())).thenReturn(50);

        Map<String, Object> result = service.runEval(req);

        assertEquals(10, result.get("total_items"));
        verify(evalTaskRunner).execute(anyString(), anyString(), any(), eq(10));
    }

    @Test
    void runEval_sampleSizeLargerThanTotal_takesTotal() {
        EvalRunRequest req = new EvalRunRequest();
        req.setDatasetVersion("v1.0");
        req.setSampleSize(100);

        // 数据库只有 20 题，sampleSize=100 不应超过总题数
        when(evalRepo.countItems(any())).thenReturn(20);

        Map<String, Object> result = service.runEval(req);

        assertEquals(20, result.get("total_items"));
    }

    @Test
    void runEval_nullCategories_countItemsWithNull() {
        EvalRunRequest req = new EvalRunRequest();
        req.setDatasetVersion("v1.0");
        req.setCategories(null);

        when(evalRepo.countItems(isNull())).thenReturn(50);

        Map<String, Object> result = service.runEval(req);

        assertEquals(50, result.get("total_items"));
        verify(evalRepo).countItems(null);
    }

    // ====================================================================
    // 11.2 评估结果列表 listResults
    // ====================================================================

    @Test
    void listResults_mapsAllFieldsCorrectly() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_001";
        r.startedAt = "2026-10-15T10:00:00Z";
        r.finishedAt = "2026-10-15T10:03:20Z";
        r.datasetVersion = "v1.0";
        r.totalItems = 50;
        r.passItems = 43;
        r.recallAt5 = 0.84;
        r.recallAt10 = 0.92;
        r.answerAccuracy = 0.78;
        r.refusalAccuracy = 0.97;
        r.citationAccuracy = 0.88;
        r.avgFirstTokenMs = 980;
        r.avgFullAnswerMs = 6200;
        when(evalRepo.listByPage(1, 10)).thenReturn(List.of(r));

        List<Map<String, Object>> items = service.listResults(1, 10);

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("eval_001", item.get("eval_run_id"));
        assertEquals("v1.0", item.get("dataset_version"));
        assertEquals(50, item.get("total_items"));
        assertEquals(43, item.get("pass_items"));
        assertEquals(0.84, item.get("recall_at_5"));
        assertEquals(0.92, item.get("recall_at_10"));
        assertEquals(0.78, item.get("answer_accuracy"));
        assertEquals(0.97, item.get("refusal_accuracy"));
        assertEquals(0.88, item.get("citation_accuracy"));
        assertEquals(980, item.get("avg_first_token_ms"));
        assertEquals(6200, item.get("avg_full_answer_ms"));
        assertEquals("FINISHED", item.get("status"));
    }

    @Test
    void listResults_runningRecord_statusIsRunning() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_002";
        r.finishedAt = null;  // 进行中
        when(evalRepo.listByPage(1, 10)).thenReturn(List.of(r));

        List<Map<String, Object>> items = service.listResults(1, 10);

        assertEquals("RUNNING", items.get(0).get("status"));
    }

    @Test
    void listResults_emptyList_returnsEmpty() {
        when(evalRepo.listByPage(1, 10)).thenReturn(new ArrayList<>());

        List<Map<String, Object>> items = service.listResults(1, 10);

        assertTrue(items.isEmpty());
    }

    @Test
    void listResults_roundsMetricsTo4Decimals() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_003";
        r.recallAt5 = 0.845678;   // 应四舍五入为 0.8457
        r.finishedAt = "done";
        when(evalRepo.listByPage(1, 10)).thenReturn(List.of(r));

        List<Map<String, Object>> items = service.listResults(1, 10);

        assertEquals(0.8457, items.get(0).get("recall_at_5"));
    }

    // ====================================================================
    // 11.3 单个评估报告 getResultDetail
    // ====================================================================

    @Test
    void getResultDetail_returnsAllFieldsAndFailItems() throws Exception {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_001";
        r.datasetVersion = "v1.0";
        r.startedAt = "2026-10-15T10:00:00Z";
        r.finishedAt = "2026-10-15T10:03:20Z";
        r.totalItems = 50;
        r.passItems = 43;
        r.recallAt5 = 0.84;
        r.recallAt10 = 0.92;
        r.answerAccuracy = 0.78;
        r.refusalAccuracy = 0.97;
        r.citationAccuracy = 0.88;
        r.avgFirstTokenMs = 980;
        r.avgFullAnswerMs = 6200;
        r.reportPath = "reports/eval_report_20261015.md";

        // fail_items_json
        FailItemDTO fail = new FailItemDTO();
        fail.setItemId("item_007");
        fail.setCategory("book_qa_located");
        fail.setQuestion("第 3.4 节提到的三个步骤是什么");
        fail.setExpected("暗示、惯常行为、奖赏");
        fail.setGot("本书中未提及相关内容");
        fail.setDiffNote("章节过滤过严导致召回为空");
        r.failItemsJson = objectMapper.writeValueAsString(List.of(fail));

        when(evalRepo.findById("eval_001")).thenReturn(r);

        Map<String, Object> detail = service.getResultDetail("eval_001");

        assertEquals("eval_001", detail.get("eval_run_id"));
        assertEquals("v1.0", detail.get("dataset_version"));
        assertEquals("reports/eval_report_20261015.md", detail.get("report_path"));
        assertEquals("FINISHED", detail.get("status"));

        List<FailItemDTO> failItems = (List<FailItemDTO>) detail.get("fail_items");
        assertEquals(1, failItems.size());
        assertEquals("item_007", failItems.get(0).getItemId());
        assertEquals("book_qa_located", failItems.get(0).getCategory());
        assertEquals("暗示、惯常行为、奖赏", failItems.get(0).getExpected());
        assertEquals("章节过滤过严导致召回为空", failItems.get(0).getDiffNote());
    }

    @Test
    void getResultDetail_notFound_throwsNotFound() {
        when(evalRepo.findById("eval_nonexist")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getResultDetail("eval_nonexist"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void getResultDetail_runningRecord_statusIsRunning() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_002";
        r.finishedAt = null;
        r.failItemsJson = null;
        when(evalRepo.findById("eval_002")).thenReturn(r);

        Map<String, Object> detail = service.getResultDetail("eval_002");

        assertEquals("RUNNING", detail.get("status"));
        // fail_items 为 null 时返回空列表
        List<FailItemDTO> failItems = (List<FailItemDTO>) detail.get("fail_items");
        assertTrue(failItems.isEmpty());
    }

    @Test
    void getResultDetail_invalidFailItemsJson_returnsEmptyList() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_003";
        r.finishedAt = "done";
        r.failItemsJson = "{ invalid json ";  // 非法 JSON
        when(evalRepo.findById("eval_003")).thenReturn(r);

        Map<String, Object> detail = service.getResultDetail("eval_003");

        // 解析失败不应抛异常，返回空列表
        List<FailItemDTO> failItems = (List<FailItemDTO>) detail.get("fail_items");
        assertTrue(failItems.isEmpty());
    }

    @Test
    void getResultDetail_blankFailItemsJson_returnsEmptyList() {
        EvalRepository.EvalResult r = new EvalRepository.EvalResult();
        r.evalRunId = "eval_004";
        r.finishedAt = "done";
        r.failItemsJson = "   ";  // 空白字符串
        when(evalRepo.findById("eval_004")).thenReturn(r);

        Map<String, Object> detail = service.getResultDetail("eval_004");

        List<FailItemDTO> failItems = (List<FailItemDTO>) detail.get("fail_items");
        assertTrue(failItems.isEmpty());
    }
}
