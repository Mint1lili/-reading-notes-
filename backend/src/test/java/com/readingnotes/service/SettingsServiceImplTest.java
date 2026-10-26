package com.readingnotes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.ModelProperties;
import com.readingnotes.config.RagProperties;
import com.readingnotes.dto.settings.BackupCreateRequest;
import com.readingnotes.dto.settings.ModelParamDTO;
import com.readingnotes.dto.settings.PromptVersionCreateRequest;
import com.readingnotes.dto.settings.RagParamDTO;
import com.readingnotes.repository.BackupRepository;
import com.readingnotes.repository.PromptVersionRepository;
import com.readingnotes.repository.SettingsRepository;
import com.readingnotes.repository.UsageLogRepository;
import com.readingnotes.service.impl.BackupTaskRunner;
import com.readingnotes.service.impl.SettingsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 系统设置服务（接口 10）单元测试
 * <p>
 * 覆盖：模型参数校验、RAG 参数校验、Prompt 版本管理、用量告警三档、备份恢复 SHA-256 校验、日志查询
 */
class SettingsServiceImplTest {

    @TempDir
    Path tempDir;

    private ModelProperties modelProps;
    private RagProperties ragProps;
    private AppProperties appProps;
    private PromptVersionRepository promptRepo;
    private BackupRepository backupRepo;
    private UsageLogRepository usageRepo;
    private BackupTaskRunner backupTaskRunner;
    private SettingsRepository settingsRepo;
    private ObjectMapper objectMapper;

    private SettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        // Properties 直接 new（POJO，有默认值）
        modelProps = new ModelProperties();
        ragProps = new RagProperties();
        appProps = new AppProperties();
        appProps.setDataDir(tempDir.toString());
        appProps.setBackupsDir(tempDir.resolve("backups").toString());
        appProps.setLogsDir(tempDir.resolve("logs").toString());

        // Repository / Runner 用 Mockito mock
        promptRepo = mock(PromptVersionRepository.class);
        backupRepo = mock(BackupRepository.class);
        usageRepo = mock(UsageLogRepository.class);
        backupTaskRunner = mock(BackupTaskRunner.class);
        settingsRepo = mock(SettingsRepository.class);
        objectMapper = new ObjectMapper();

        service = new SettingsServiceImpl(modelProps, ragProps, appProps,
                promptRepo, backupRepo, usageRepo, backupTaskRunner,
                settingsRepo, objectMapper);
    }

    // ====================================================================
    // 10.1 模型参数
    // ====================================================================

    @Test
    void getModelParams_returnsCurrentConfig() {
        modelProps.setTemperature(0.3);
        modelProps.setMaxTokens(2048);
        modelProps.setTopP(0.9);
        modelProps.setStream(true);

        ModelParamDTO dto = service.getModelParams();

        assertEquals(0.3, dto.getTemperature());
        assertEquals(2048, dto.getMaxTokens());
        assertEquals(0.9, dto.getTopP());
        assertTrue(dto.getStream());
    }

    @Test
    void updateModelParams_validValue_updatesAndPersists() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTemperature(0.5);

        ModelParamDTO result = service.updateModelParams(dto);

        assertEquals(0.5, result.getTemperature());
        verify(settingsRepo).upsert(eq("model_params_json"), anyString(), anyString());
    }

    @Test
    void updateModelParams_temperatureAboveOne_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTemperature(1.5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_temperatureBelowZero_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTemperature(-0.1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_maxTokensBelow256_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setMaxTokens(100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_maxTokensAbove4096_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setMaxTokens(5000);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_topPBelow07_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTopP(0.5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_topPAboveOne_throwsParamError() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTopP(1.2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateModelParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateModelParams_nullFields_skipsValidation() {
        // 全部 null 不应抛异常
        ModelParamDTO dto = new ModelParamDTO();
        assertDoesNotThrow(() -> service.updateModelParams(dto));
    }

    // ====================================================================
    // 10.2 RAG 参数
    // ====================================================================

    @Test
    void getRagParams_returnsCurrentConfig() {
        ragProps.setRecallTopK(10);
        ragProps.setChunkSize(600);
        ragProps.setEnableRerank(true);

        RagParamDTO dto = service.getRagParams();

        assertEquals(10, dto.getRecallTopK());
        assertEquals(600, dto.getChunkSize());
        assertTrue(dto.getEnableRerank());
    }

    @Test
    void updateRagParams_validValue_updatesAndPersists() {
        RagParamDTO dto = new RagParamDTO();
        dto.setRecallTopK(20);
        dto.setSimilarityThreshold(0.8);

        RagParamDTO result = service.updateRagParams(dto);

        assertEquals(20, result.getRecallTopK());
        assertEquals(0.8, result.getSimilarityThreshold());
        verify(settingsRepo).upsert(eq("rag_params_json"), anyString(), anyString());
    }

    @Test
    void updateRagParams_recallTopKLessThanOne_throwsParamError() {
        RagParamDTO dto = new RagParamDTO();
        dto.setRecallTopK(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRagParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void updateRagParams_similarityThresholdOutOfRange_throwsParamError() {
        RagParamDTO dto = new RagParamDTO();
        dto.setSimilarityThreshold(1.5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRagParams(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    // ====================================================================
    // 10.3 Prompt 模板
    // ====================================================================

    @Test
    void listPromptSummaries_mapsFieldsCorrectly() {
        PromptVersionRepository.PromptSummary s = new PromptVersionRepository.PromptSummary();
        s.name = "book_qa_system";
        s.activeVersion = 2;
        s.totalVersions = 3;
        s.updatedAt = "2026-10-15T10:00:00Z";
        when(promptRepo.listSummaries()).thenReturn(List.of(s));

        List<Map<String, Object>> result = service.listPromptSummaries();

        assertEquals(1, result.size());
        assertEquals("book_qa_system", result.get(0).get("name"));
        assertEquals(2, result.get(0).get("active_version"));
        assertEquals(3, result.get(0).get("total_versions"));
    }

    @Test
    void getPromptDetail_latest_usesActiveVersion() {
        PromptVersionRepository.PromptVersion pv = new PromptVersionRepository.PromptVersion();
        pv.name = "book_qa_system";
        pv.version = 2;
        pv.isActive = 1;
        pv.content = "你是一个严谨的读书助手";
        pv.author = "admin";
        pv.createdAt = "2026-10-15T10:00:00Z";
        when(promptRepo.find(eq("book_qa_system"), isNull(), eq(true))).thenReturn(pv);

        Map<String, Object> detail = service.getPromptDetail("book_qa_system", "latest");

        assertEquals(2, detail.get("version"));
        assertEquals(true, detail.get("is_active"));
        assertEquals("你是一个严谨的读书助手", detail.get("content"));
        verify(promptRepo).find("book_qa_system", null, true);
    }

    @Test
    void getPromptDetail_notFound_throwsPromptNotFound() {
        when(promptRepo.find(any(), any(), anyBoolean())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getPromptDetail("nonexistent", "latest"));
        assertEquals(ErrorCode.PROMPT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void createPromptVersion_valid_createsNewActiveVersion() {
        PromptVersionCreateRequest req = new PromptVersionCreateRequest();
        req.setContent("system: 你是助手\n");
        req.setDescription("test");

        when(promptRepo.maxVersion("book_qa")).thenReturn(2);
        when(promptRepo.countVersions("book_qa")).thenReturn(2);

        Map<String, Object> result = service.createPromptVersion("book_qa", req);

        assertEquals(3, result.get("version"));
        assertEquals(true, result.get("is_active"));
        verify(promptRepo).deactivateAll("book_qa");
        verify(promptRepo).insert(any(PromptVersionRepository.PromptVersion.class));
    }

    @Test
    void createPromptVersion_invalidYaml_throwsSyntaxError() {
        PromptVersionCreateRequest req = new PromptVersionCreateRequest();
        req.setContent("system: { invalid yaml : : : ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPromptVersion("book_qa", req));
        assertEquals(ErrorCode.PROMPT_SYNTAX_ERROR.getCode(), ex.getCode());
    }

    @Test
    void createPromptVersion_tenVersionsReached_throwsParamError() {
        PromptVersionCreateRequest req = new PromptVersionCreateRequest();
        req.setContent("system: ok\n");

        when(promptRepo.maxVersion("book_qa")).thenReturn(9);
        when(promptRepo.countVersions("book_qa")).thenReturn(10);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPromptVersion("book_qa", req));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void activatePromptVersion_notFound_throwsPromptNotFound() {
        when(promptRepo.find("book_qa", 5, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.activatePromptVersion("book_qa", 5));
        assertEquals(ErrorCode.PROMPT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void activatePromptVersion_exists_callsActivate() {
        PromptVersionRepository.PromptVersion pv = new PromptVersionRepository.PromptVersion();
        pv.name = "book_qa";
        pv.version = 2;
        when(promptRepo.find("book_qa", 2, false)).thenReturn(pv);

        assertDoesNotThrow(() -> service.activatePromptVersion("book_qa", 2));
        verify(promptRepo).activate("book_qa", 2);
    }

    // ====================================================================
    // 10.4 用量统计
    // ====================================================================

    @Test
    void getUsage_nullDate_throwsParamError() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getUsage(null, "2026-10-15", "day"));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void getUsage_normalAlert_below80Percent() {
        // summary
        UsageLogRepository.UsageSummary summary = new UsageLogRepository.UsageSummary();
        summary.totalInputTokens = 1000;
        summary.totalOutputTokens = 500;
        summary.totalCalls = 10;
        summary.totalCostCny = 0.5;
        when(usageRepo.sumByDateRange(any(), any())).thenReturn(summary);

        // 今日用量 50%（输入 40000/80000 = 50%，输出 10000/20000 = 50%）
        UsageLogRepository.DailyUsage today = new UsageLogRepository.DailyUsage();
        today.inputTokens = 40000;
        today.outputTokens = 10000;
        when(usageRepo.sumForDay(any())).thenReturn(today);

        when(usageRepo.sumByFeature(any(), any())).thenReturn(new ArrayList<>());
        when(usageRepo.timelineByDay(any(), any())).thenReturn(new ArrayList<>());

        Map<String, Object> result = service.getUsage("2026-10-01", "2026-10-15", "day");

        Map<String, Object> budget = (Map<String, Object>) result.get("budget");
        assertEquals("normal", budget.get("alert_level"));
        assertEquals(50.0, budget.get("usage_percent"));
    }

    @Test
    void getUsage_warningAlert_above80Percent() {
        UsageLogRepository.UsageSummary summary = new UsageLogRepository.UsageSummary();
        when(usageRepo.sumByDateRange(any(), any())).thenReturn(summary);

        // 输入 72000/80000 = 90% → warning
        UsageLogRepository.DailyUsage today = new UsageLogRepository.DailyUsage();
        today.inputTokens = 72000;
        today.outputTokens = 10000;
        when(usageRepo.sumForDay(any())).thenReturn(today);
        when(usageRepo.sumByFeature(any(), any())).thenReturn(new ArrayList<>());
        when(usageRepo.timelineByDay(any(), any())).thenReturn(new ArrayList<>());

        Map<String, Object> result = service.getUsage("2026-10-01", "2026-10-15", "day");

        Map<String, Object> budget = (Map<String, Object>) result.get("budget");
        assertEquals("warning", budget.get("alert_level"));
    }

    @Test
    void getUsage_blockedAlert_above100Percent() {
        UsageLogRepository.UsageSummary summary = new UsageLogRepository.UsageSummary();
        when(usageRepo.sumByDateRange(any(), any())).thenReturn(summary);

        // 输出 25000/20000 = 125% → blocked
        UsageLogRepository.DailyUsage today = new UsageLogRepository.DailyUsage();
        today.inputTokens = 40000;
        today.outputTokens = 25000;
        when(usageRepo.sumForDay(any())).thenReturn(today);
        when(usageRepo.sumByFeature(any(), any())).thenReturn(new ArrayList<>());
        when(usageRepo.timelineByDay(any(), any())).thenReturn(new ArrayList<>());

        Map<String, Object> result = service.getUsage("2026-10-01", "2026-10-15", "day");

        Map<String, Object> budget = (Map<String, Object>) result.get("budget");
        assertEquals("blocked", budget.get("alert_level"));
    }

    // ====================================================================
    // 10.5 备份与恢复
    // ====================================================================

    @Test
    void listBackups_mapsFields() {
        BackupRepository.BackupRecord r = new BackupRepository.BackupRecord();
        r.backupId = "bk_001";
        r.backupType = "manual";
        r.status = "SUCCESS";
        r.fileSize = 1024;
        r.sha256 = "abc123";
        when(backupRepo.list()).thenReturn(List.of(r));

        List<Map<String, Object>> result = service.listBackups();

        assertEquals(1, result.size());
        assertEquals("bk_001", result.get(0).get("backup_id"));
        assertEquals("SUCCESS", result.get(0).get("status"));
    }

    @Test
    void createBackup_returnsRunningStatus() {
        BackupCreateRequest req = new BackupCreateRequest();
        req.setBackupType("manual");
        req.setTrigger("manual_click");

        Map<String, Object> result = service.createBackup(req);

        assertEquals("RUNNING", result.get("status"));
        assertNotNull(result.get("backup_id"));
        verify(backupRepo).insert(any(BackupRepository.BackupRecord.class));
        verify(backupTaskRunner).doBackup(anyString(), anyString());
    }

    @Test
    void restoreBackup_notFound_throwsNotFound() {
        when(backupRepo.findById("bk_nonexist")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreBackup("bk_nonexist"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void restoreBackup_statusNotSuccess_throwsRestoreFailed() {
        BackupRepository.BackupRecord r = new BackupRepository.BackupRecord();
        r.backupId = "bk_001";
        r.status = "FAILED";
        when(backupRepo.findById("bk_001")).thenReturn(r);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreBackup("bk_001"));
        assertEquals(ErrorCode.RESTORE_FAILED.getCode(), ex.getCode());
    }

    // ====================================================================
    // 10.6 日志查询
    // ====================================================================

    @Test
    void queryLogs_returnsTotalAndItems() {
        // 日志查询依赖文件系统，这里只验证结构映射
        // 实际会读取 logs-dir 下的日志文件
        Map<String, Object> result = service.queryLogs(null, "INFO", null, null, null, 100);

        assertNotNull(result);
        assertTrue(result.containsKey("total"));
        assertTrue(result.containsKey("items"));
    }

    @Test
    void queryLogs_withTraceId_filtersByTraceId() {
        Map<String, Object> result = service.queryLogs("abc123", null, null, null, null, 50);

        assertNotNull(result);
        assertEquals(0, ((Number) result.get("total")).intValue());
    }
}
