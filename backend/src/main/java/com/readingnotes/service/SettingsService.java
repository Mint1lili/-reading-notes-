package com.readingnotes.service;

import com.readingnotes.dto.settings.BackupCreateRequest;
import com.readingnotes.dto.settings.ModelParamDTO;
import com.readingnotes.dto.settings.PromptVersionCreateRequest;
import com.readingnotes.dto.settings.RagParamDTO;

import java.util.List;
import java.util.Map;

/**
 * 系统设置服务接口（接口 10）
 */
public interface SettingsService {

    // === 10.1 模型参数 ===
    ModelParamDTO getModelParams();
    ModelParamDTO updateModelParams(ModelParamDTO dto);

    // === 10.2 RAG 参数 ===
    RagParamDTO getRagParams();
    RagParamDTO updateRagParams(RagParamDTO dto);

    // === 10.3 Prompt 模板 ===
    List<Map<String, Object>> listPromptSummaries();
    Map<String, Object> getPromptDetail(String name, String version);
    Map<String, Object> createPromptVersion(String name, PromptVersionCreateRequest req);
    void activatePromptVersion(String name, int version);

    // === 10.4 用量统计 ===
    Map<String, Object> getUsage(String startDate, String endDate, String groupBy);

    // === 10.5 备份与恢复 ===
    List<Map<String, Object>> listBackups();
    Map<String, Object> createBackup(BackupCreateRequest req);
    Map<String, Object> restoreBackup(String backupId);

    // === 10.6 日志查询 ===
    Map<String, Object> queryLogs(String traceId, String level, String module,
                                  String start, String end, int limit);
}
