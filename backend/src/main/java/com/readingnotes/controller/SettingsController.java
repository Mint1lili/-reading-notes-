package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.dto.settings.BackupCreateRequest;
import com.readingnotes.dto.settings.ModelParamDTO;
import com.readingnotes.dto.settings.PromptVersionCreateRequest;
import com.readingnotes.dto.settings.RagParamDTO;
import com.readingnotes.service.SettingsService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置接口（API 文档 §10）
 * 10.1 模型参数   /settings/model
 * 10.2 RAG 参数   /settings/rag
 * 10.3 Prompt 模板 /settings/prompts
 * 10.4 用量统计   /settings/usage
 * 10.5 备份与恢复 /settings/backups
 * 10.6 日志查询   /settings/logs
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ====================================================================
    // 10.1 模型参数
    // ====================================================================

    /** 查询当前模型参数 */
    @GetMapping("/model")
    public Result<ModelParamDTO> getModel() {
        return Result.ok(settingsService.getModelParams());
    }

    /** 更新模型参数（越界返回 10001） */
    @PutMapping("/model")
    public Result<ModelParamDTO> updateModel(@Valid @RequestBody ModelParamDTO dto) {
        return Result.ok(settingsService.updateModelParams(dto));
    }

    // ====================================================================
    // 10.2 RAG 参数
    // ====================================================================

    /** 查询当前 RAG 参数 */
    @GetMapping("/rag")
    public Result<RagParamDTO> getRag() {
        return Result.ok(settingsService.getRagParams());
    }

    /** 更新 RAG 参数（支持单字段，立即生效） */
    @PutMapping("/rag")
    public Result<RagParamDTO> updateRag(@RequestBody RagParamDTO dto) {
        return Result.ok(settingsService.updateRagParams(dto));
    }

    // ====================================================================
    // 10.3 Prompt 模板
    // ====================================================================

    /** Prompt 列表（名称 + 活跃版本 + 总版本数 + 更新时间） */
    @GetMapping("/prompts")
    public Result<Map<String, Object>> listPrompts() {
        Map<String, Object> data = new HashMap<>();
        data.put("items", settingsService.listPromptSummaries());
        return Result.ok(data);
    }

    /** Prompt 详情（version 默认取最新活跃版，传数字可查历史版） */
    @GetMapping("/prompts/{name}")
    public Result<Map<String, Object>> getPrompt(
            @PathVariable String name,
            @RequestParam(required = false) String version) {
        return Result.ok(settingsService.getPromptDetail(name, version));
    }

    /** 保存 Prompt 新版本（版本号 +1，旧版本自动 inactive；YAML 语法错误 → 60002） */
    @PostMapping("/prompts/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Object>> createPromptVersion(
            @PathVariable String name,
            @Valid @RequestBody PromptVersionCreateRequest req) {
        return Result.ok(settingsService.createPromptVersion(name, req));
    }

    /** 回滚 / 激活指定版本 */
    @PostMapping("/prompts/{name}/versions/{version}/activate")
    public Result<Void> activatePrompt(
            @PathVariable String name,
            @PathVariable int version) {
        settingsService.activatePromptVersion(name, version);
        return Result.ok();
    }

    // ====================================================================
    // 10.4 用量统计
    // ====================================================================

    /** 用量统计（summary / budget / by_feature / timeline） */
    @GetMapping("/usage")
    public Result<Map<String, Object>> getUsage(
            @RequestParam String start_date,
            @RequestParam String end_date,
            @RequestParam(defaultValue = "day") String group_by) {
        return Result.ok(settingsService.getUsage(start_date, end_date, group_by));
    }

    // ====================================================================
    // 10.5 备份与恢复
    // ====================================================================

    /** 备份列表 */
    @GetMapping("/backups")
    public Result<Map<String, Object>> listBackups() {
        Map<String, Object> data = new HashMap<>();
        data.put("items", settingsService.listBackups());
        return Result.ok(data);
    }

    /** 创建备份（异步，立即返回 RUNNING；zip 内容 = SQLite + uploads + Chroma） */
    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Map<String, Object>> createBackup(
            @Valid @RequestBody(required = false) BackupCreateRequest req) {
        BackupCreateRequest body = req != null ? req : new BackupCreateRequest();
        return Result.ok(settingsService.createBackup(body));
    }

    /** 恢复备份（SHA-256 校验失败 → 60004；恢复后服务会重启） */
    @PostMapping("/backups/{backup_id}/restore")
    public Result<Map<String, Object>> restoreBackup(@PathVariable String backup_id) {
        Map<String, Object> data = settingsService.restoreBackup(backup_id);
        return Result.ok(data, "恢复已开始，服务将重启");
    }

    // ====================================================================
    // 10.6 日志查询
    // ====================================================================

    /** 日志查询（trace_id / level / module / 时间区间 / 分页） */
    @GetMapping("/logs")
    public Result<Map<String, Object>> queryLogs(
            @RequestParam(required = false) String trace_id,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(settingsService.queryLogs(trace_id, level, module, start, end, limit));
    }
}
