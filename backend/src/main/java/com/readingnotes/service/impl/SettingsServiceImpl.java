package com.readingnotes.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.readingnotes.service.SettingsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 系统设置服务实现（接口 10）
 * - 模型参数 / RAG 参数：@PostConstruct 启动时从 DB 加载覆盖默认值；PUT 热更新 + 持久化
 * - Prompt 模板：直接读写 prompt_versions 表
 * - 备份 / 用量 / 日志：各自独立实现
 */
@Slf4j
@Service
public class SettingsServiceImpl implements SettingsService {

    /** settings 表 key：序列化后的完整 JSON */
    private static final String KEY_MODEL = "model_params_json";
    private static final String KEY_RAG   = "rag_params_json";

    private final ModelProperties modelProperties;
    private final RagProperties ragProperties;
    private final AppProperties appProperties;
    private final PromptVersionRepository promptRepo;
    private final BackupRepository backupRepo;
    private final UsageLogRepository usageRepo;
    private final BackupTaskRunner backupTaskRunner;
    private final SettingsRepository settingsRepo;
    private final ObjectMapper objectMapper;

    public SettingsServiceImpl(ModelProperties modelProperties,
                               RagProperties ragProperties,
                               AppProperties appProperties,
                               PromptVersionRepository promptRepo,
                               BackupRepository backupRepo,
                               UsageLogRepository usageRepo,
                               BackupTaskRunner backupTaskRunner,
                               SettingsRepository settingsRepo,
                               ObjectMapper objectMapper) {
        this.modelProperties = modelProperties;
        this.ragProperties = ragProperties;
        this.appProperties = appProperties;
        this.promptRepo = promptRepo;
        this.backupRepo = backupRepo;
        this.usageRepo = usageRepo;
        this.backupTaskRunner = backupTaskRunner;
        this.settingsRepo = settingsRepo;
        this.objectMapper = objectMapper;
    }

    /** 服务启动时：如果 DB 中有持久化的设置，用它覆盖 application.yml 默认值 */
    @PostConstruct
    public void loadPersistedSettings() {
        try {
            String modelJson = settingsRepo.find(KEY_MODEL);
            if (modelJson != null && !modelJson.isBlank()) {
                ModelParamDTO persisted = objectMapper.readValue(modelJson, ModelParamDTO.class);
                applyModelPatch(persisted);
                log.info("loaded model params from DB: {}", modelJson);
            }
            String ragJson = settingsRepo.find(KEY_RAG);
            if (ragJson != null && !ragJson.isBlank()) {
                RagParamDTO persisted = objectMapper.readValue(ragJson, RagParamDTO.class);
                applyRagPatch(persisted);
                log.info("loaded rag params from DB: {}", ragJson);
            }
        } catch (Exception e) {
            log.warn("failed to load persisted settings, using defaults: {}", e.getMessage());
        }
    }

    /** 将 DTO 中非 null 字段应用到 @ConfigurationProperties Bean（启动加载 & PUT 共用） */
    private void applyModelPatch(ModelParamDTO dto) {
        if (dto.getTemperature() != null) modelProperties.setTemperature(dto.getTemperature());
        if (dto.getMaxTokens() != null) modelProperties.setMaxTokens(dto.getMaxTokens());
        if (dto.getTopP() != null) modelProperties.setTopP(dto.getTopP());
        if (dto.getStream() != null) modelProperties.setStream(dto.getStream());
    }

    private void applyRagPatch(RagParamDTO dto) {
        if (dto.getRecallTopK() != null) ragProperties.setRecallTopK(dto.getRecallTopK());
        if (dto.getRerankTopN() != null) ragProperties.setRerankTopN(dto.getRerankTopN());
        if (dto.getSimilarityThreshold() != null) ragProperties.setSimilarityThreshold(dto.getSimilarityThreshold());
        if (dto.getChunkSize() != null) ragProperties.setChunkSize(dto.getChunkSize());
        if (dto.getOverlap() != null) ragProperties.setOverlap(dto.getOverlap());
        if (dto.getMultiTurnN() != null) ragProperties.setMultiTurnN(dto.getMultiTurnN());
        if (dto.getEnableRerank() != null) ragProperties.setEnableRerank(dto.getEnableRerank());
        if (dto.getEnableMetadataFilter() != null) ragProperties.setEnableMetadataFilter(dto.getEnableMetadataFilter());
    }

    /** 序列化 DTO 为 JSON 并存入 settings 表 */
    private void persistModel() {
        try {
            String json = objectMapper.writeValueAsString(getModelParams());
            settingsRepo.upsert(KEY_MODEL, json, "模型参数（热更新持久化）");
        } catch (JsonProcessingException e) {
            log.error("persist model params failed", e);
        }
    }

    private void persistRag() {
        try {
            String json = objectMapper.writeValueAsString(getRagParams());
            settingsRepo.upsert(KEY_RAG, json, "RAG 参数（热更新持久化）");
        } catch (JsonProcessingException e) {
            log.error("persist rag params failed", e);
        }
    }

    // ====================================================================
    // 10.1 模型参数
    // ====================================================================

    @Override
    public ModelParamDTO getModelParams() {
        ModelParamDTO dto = new ModelParamDTO();
        dto.setTemperature(modelProperties.getTemperature());
        dto.setMaxTokens(modelProperties.getMaxTokens());
        dto.setTopP(modelProperties.getTopP());
        dto.setStream(modelProperties.isStream());
        return dto;
    }

    @Override
    public ModelParamDTO updateModelParams(ModelParamDTO dto) {
        // 手动越界校验（Controller 已做 @Valid，此处防御性再检查一次）
        if (dto.getTemperature() != null
                && (dto.getTemperature() < 0.0 || dto.getTemperature() > 1.0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "temperature 必须在 [0, 1] 区间");
        }
        if (dto.getMaxTokens() != null
                && (dto.getMaxTokens() < 256 || dto.getMaxTokens() > 4096)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "max_tokens 必须在 [256, 4096] 区间");
        }
        if (dto.getTopP() != null
                && (dto.getTopP() < 0.7 || dto.getTopP() > 1.0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "top_p 必须在 [0.7, 1] 区间");
        }

        // 热更新 + 持久化（复用 applyModelPatch 消除重复）
        applyModelPatch(dto);
        persistModel();

        log.info("model params updated: temperature={}, maxTokens={}, topP={}, stream={}",
                modelProperties.getTemperature(), modelProperties.getMaxTokens(),
                modelProperties.getTopP(), modelProperties.isStream());

        return getModelParams();
    }

    // ====================================================================
    // 10.2 RAG 参数
    // ====================================================================

    @Override
    public RagParamDTO getRagParams() {
        RagParamDTO dto = new RagParamDTO();
        dto.setRecallTopK(ragProperties.getRecallTopK());
        dto.setRerankTopN(ragProperties.getRerankTopN());
        dto.setSimilarityThreshold(ragProperties.getSimilarityThreshold());
        dto.setChunkSize(ragProperties.getChunkSize());
        dto.setOverlap(ragProperties.getOverlap());
        dto.setMultiTurnN(ragProperties.getMultiTurnN());
        dto.setEnableRerank(ragProperties.isEnableRerank());
        dto.setEnableMetadataFilter(ragProperties.isEnableMetadataFilter());
        return dto;
    }

    @Override
    public RagParamDTO updateRagParams(RagParamDTO dto) {
        // 校验
        if (dto.getRecallTopK() != null && dto.getRecallTopK() < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recall_top_k 必须 >= 1");
        }
        if (dto.getRerankTopN() != null && dto.getRerankTopN() < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "rerank_top_n 必须 >= 1");
        }
        if (dto.getSimilarityThreshold() != null
                && (dto.getSimilarityThreshold() < 0.0 || dto.getSimilarityThreshold() > 1.0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "similarity_threshold 必须在 [0, 1] 区间");
        }

        // 热更新 + 持久化（复用 applyRagPatch 消除重复）
        applyRagPatch(dto);
        persistRag();

        log.info("rag params updated: {}", dto);
        return getRagParams();
    }

    // ====================================================================
    // 10.3 Prompt 模板
    // ====================================================================

    @Override
    public List<Map<String, Object>> listPromptSummaries() {
        return promptRepo.listSummaries().stream().map(s -> {
            Map<String, Object> item = new HashMap<>();
            item.put("name", s.name);
            item.put("active_version", s.activeVersion);
            item.put("total_versions", s.totalVersions);
            item.put("updated_at", s.updatedAt);
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getPromptDetail(String name, String versionParam) {
        boolean activeOnly = (versionParam == null || versionParam.isBlank()
                || "latest".equalsIgnoreCase(versionParam)
                || "active".equalsIgnoreCase(versionParam));
        Integer version = null;
        if (!activeOnly) {
            try {
                version = Integer.parseInt(versionParam);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "version 参数格式错误");
            }
        }

        PromptVersionRepository.PromptVersion pv = promptRepo.find(name, version, activeOnly);
        if (pv == null) {
            throw new BusinessException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt 模板不存在: " + name);
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("name", pv.name);
        detail.put("version", pv.version);
        detail.put("is_active", pv.isActive == 1);
        detail.put("content", pv.content);
        detail.put("description", pv.description);
        detail.put("author", pv.author);
        detail.put("created_at", pv.createdAt);
        return detail;
    }

    @Override
    public Map<String, Object> createPromptVersion(String name, PromptVersionCreateRequest req) {
        // YAML 语法校验 — 使用 SnakeYAML 解析一次来检测语法错误
        try {
            Yaml yaml = new Yaml();
            yaml.load(new ByteArrayInputStream(req.getContent().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROMPT_SYNTAX_ERROR,
                    "Prompt 模板 YAML 语法错误: " + e.getMessage());
        }

        // 计算新版本号
        int nextVersion = promptRepo.maxVersion(name) + 1;
        int totalVersions = promptRepo.countVersions(name);

        // 超过 10 版拒绝（实际归档到 archive/ 的逻辑后续扩展）
        if (totalVersions >= 10) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该 Prompt 模板已达 10 版上限，请先归档旧版本");
        }

        // 旧版本全部 inactive
        promptRepo.deactivateAll(name);

        // 插入新版本（active=1）
        PromptVersionRepository.PromptVersion pv = new PromptVersionRepository.PromptVersion();
        pv.name = name;
        pv.version = nextVersion;
        pv.content = req.getContent();
        pv.isActive = 1;
        pv.author = "admin";  // 简化，实际应取登录用户
        pv.description = req.getDescription();
        pv.createdAt = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        promptRepo.insert(pv);

        log.info("new prompt version created: name={}, version={}", name, nextVersion);

        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        result.put("version", nextVersion);
        result.put("is_active", true);
        return result;
    }

    @Override
    public void activatePromptVersion(String name, int version) {
        PromptVersionRepository.PromptVersion pv = promptRepo.find(name, version, false);
        if (pv == null) {
            throw new BusinessException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt 模板版本不存在: " + name + " v" + version);
        }
        promptRepo.activate(name, version);
        log.info("prompt version activated: name={}, version={}", name, version);
    }

    // ====================================================================
    // 10.4 用量统计
    // ====================================================================

    @Override
    public Map<String, Object> getUsage(String startDate, String endDate, String groupBy) {
        if (startDate == null || endDate == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "start_date 和 end_date 不能为空");
        }

        UsageLogRepository.UsageSummary summary = usageRepo.sumByDateRange(startDate, endDate);

        // budget（今日 vs 阈值）
        String today = LocalDate.now().toString();
        UsageLogRepository.DailyUsage todayUsage = usageRepo.sumForDay(today);

        long dailyInputLimit = modelProperties.getDailyInputLimit();
        long dailyOutputLimit = modelProperties.getDailyOutputLimit();
        long usedInput = todayUsage.inputTokens;
        long usedOutput = todayUsage.outputTokens;

        double inputPercent = dailyInputLimit > 0 ? (double) usedInput / dailyInputLimit * 100 : 0;
        double outputPercent = dailyOutputLimit > 0 ? (double) usedOutput / dailyOutputLimit * 100 : 0;
        double usagePercent = Math.max(inputPercent, outputPercent);

        String alertLevel;
        if (usagePercent >= 100) {
            alertLevel = "blocked";
        } else if (usagePercent >= 80) {
            alertLevel = "warning";
        } else {
            alertLevel = "normal";
        }

        Map<String, Object> budget = new HashMap<>();
        budget.put("daily_input_limit", dailyInputLimit);
        budget.put("daily_output_limit", dailyOutputLimit);
        budget.put("today_used_input", usedInput);
        budget.put("today_used_output", usedOutput);
        budget.put("usage_percent", Math.round(usagePercent * 10.0) / 10.0);
        budget.put("alert_level", alertLevel);

        List<UsageLogRepository.FeatureUsage> featureList = usageRepo.sumByFeature(startDate, endDate);
        List<Map<String, Object>> byFeature = featureList.stream().map(f -> {
            Map<String, Object> item = new HashMap<>();
            item.put("feature", f.feature);
            item.put("calls", f.calls);
            item.put("input_tokens", f.inputTokens);
            item.put("output_tokens", f.outputTokens);
            return item;
        }).collect(Collectors.toList());

        List<UsageLogRepository.DailyUsage> timelineRaw = usageRepo.timelineByDay(startDate, endDate);
        List<Map<String, Object>> timeline = timelineRaw.stream().map(d -> {
            Map<String, Object> item = new HashMap<>();
            item.put("date", d.date);
            item.put("input_tokens", d.inputTokens);
            item.put("output_tokens", d.outputTokens);
            item.put("calls", d.calls);
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();

        Map<String, Object> summaryMap = new HashMap<>();
        summaryMap.put("total_input_tokens", summary.totalInputTokens);
        summaryMap.put("total_output_tokens", summary.totalOutputTokens);
        summaryMap.put("total_calls", summary.totalCalls);
        summaryMap.put("total_cost_cny", Math.round(summary.totalCostCny * 100.0) / 100.0);
        result.put("summary", summaryMap);

        result.put("budget", budget);
        result.put("by_feature", byFeature);
        result.put("timeline", timeline);

        return result;
    }

    // ====================================================================
    // 10.5 备份与恢复
    // ====================================================================

    @Override
    public List<Map<String, Object>> listBackups() {
        return backupRepo.list().stream().map(r -> {
            Map<String, Object> item = new HashMap<>();
            item.put("backup_id", r.backupId);
            item.put("backup_type", r.backupType);
            item.put("trigger", r.trigger);
            item.put("file_size", r.fileSize);
            item.put("sha256", r.sha256);
            item.put("status", r.status);
            item.put("started_at", r.startedAt);
            item.put("finished_at", r.finishedAt);
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> createBackup(BackupCreateRequest req) {
        String backupId = "bk_" + System.currentTimeMillis();
        String startedAt = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        String backupsDir = appProperties.getBackupsDir();

        // 确保目录存在
        new File(backupsDir).mkdirs();
        String filePath = backupsDir + File.separator + backupId + ".zip";

        // 先写一条 RUNNING 记录
        BackupRepository.BackupRecord record = new BackupRepository.BackupRecord();
        record.backupId = backupId;
        record.backupType = req.getBackupType() != null ? req.getBackupType() : "manual";
        record.trigger = req.getTrigger() != null ? req.getTrigger() : "manual_click";
        record.filePath = filePath;
        record.fileSize = 0;
        record.sha256 = "";
        record.includedObjectsJson = "{}";
        record.startedAt = startedAt;
        record.finishedAt = null;
        record.status = "RUNNING";
        record.errorMessage = null;
        backupRepo.insert(record);

        // 通过独立 Bean 异步执行（Spring AOP 代理才能拦截 @Async）
        backupTaskRunner.doBackup(backupId, filePath);

        // 立即返回 RUNNING 状态，HTTP 202 Accepted
        Map<String, Object> result = new HashMap<>();
        result.put("backup_id", backupId);
        result.put("status", "RUNNING");
        return result;
    }

    /** 恢复备份：校验 SHA-256 → 真正解包覆盖 */
    @Override
    public Map<String, Object> restoreBackup(String backupId) {
        BackupRepository.BackupRecord record = backupRepo.findById(backupId);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "备份不存在: " + backupId);
        }
        if (!"SUCCESS".equals(record.status)) {
            throw new BusinessException(ErrorCode.RESTORE_FAILED,
                    "备份文件不完整（status=" + record.status + "）");
        }

        // 校验 SHA-256
        File zipFile = new File(record.filePath);
        if (!zipFile.exists()) {
            throw new BusinessException(ErrorCode.RESTORE_FAILED, "备份文件缺失");
        }

        // 使用 BackupTaskRunner 的 sha256 方法（已抽取）
        String actualSha = backupTaskRunner.sha256OfFile(zipFile);
        boolean verified = actualSha.equalsIgnoreCase(record.sha256);
        if (!verified) {
            log.error("restore SHA-256 mismatch: expected={}, actual={}", record.sha256, actualSha);
            throw new BusinessException(ErrorCode.RESTORE_FAILED,
                    "SHA-256 校验失败，阻断恢复");
        }

        // 真正解包覆盖（uploads/ → uploads，prompts/ → prompts，sqlite/ → data 根）
        String restoreRoot = appProperties.getDataDir();
        try (ZipFile zf = new ZipFile(zipFile)) {
            Path rootPath = Paths.get(restoreRoot).toAbsolutePath().normalize();
            zf.entries().asIterator().forEachRemaining(entry -> {
                try {
                    Path target = rootPath.resolve(entry.getName()).normalize();
                    // 防 Zip Slip：确保解压后的路径不逃脱目标根目录
                    if (!target.startsWith(rootPath)) {
                        throw new IOException("zip entry escapes target dir: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (InputStream is = zf.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(target.toFile())) {
                            is.transferTo(fos);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException io) {
                throw new BusinessException(ErrorCode.RESTORE_FAILED,
                        "解包失败: " + io.getMessage());
            }
            throw re;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RESTORE_FAILED, "备份文件损坏: " + e.getMessage());
        }

        log.warn("restore initiated: backup_id={}, verified={}, service will restart", backupId, verified);

        Map<String, Object> result = new HashMap<>();
        result.put("backup_id", backupId);
        result.put("verified", verified);
        return result;
    }

    // ====================================================================
    // 10.6 日志查询
    // ====================================================================

    /** Spring Boot 默认日志时间格式：yyyy-MM-dd HH:mm:ss.SSS */
    private static final DateTimeFormatter BOOT_LOG_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** ISO-8601 UTC：2026-10-15T08:00:00Z */
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT;

    /** 匹配日志行开头的时间戳 */
    private static final Pattern TS_PATTERN =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})");

    /** 匹配 trace= 或 [trace=xxx] */
    private static final Pattern TRACE_PATTERN =
            Pattern.compile("trace[=:]([a-fA-F0-9]{16,})");

    /** 匹配 [module] 或 空格 module.xxx 形式 */
    private static final Pattern MODULE_PATTERN =
            Pattern.compile("\\[([\\w.]+)\\]| ([a-z]+\\.[\\w.]+)");

    @Override
    public Map<String, Object> queryLogs(String traceId, String level, String module,
                                          String start, String end, int limit) {
        // 将 start/end 参数（ISO-8601）解析为 LocalDateTime，用于与日志行时间戳比较
        LocalDateTime startLdt = parseQueryTime(start);
        LocalDateTime endLdt = parseQueryTime(end);

        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;

        String logFilePath = appProperties.getLogsDir() + File.separator + "app.log";
        File logFile = new File(logFilePath);

        if (logFile.exists()) {
            try (Stream<String> lines = Files.lines(logFile.toPath(), StandardCharsets.UTF_8)) {
                // 先收集所有匹配行
                List<LogEntry> matched = lines
                        .map(this::parseLogLine)
                        .filter(e -> e != null)
                        .filter(e -> traceId == null || traceId.equals(e.traceId))
                        .filter(e -> level == null || level.equalsIgnoreCase(e.level))
                        .filter(e -> module == null || (e.module != null && e.module.contains(module)))
                        .filter(e -> startLdt == null || e.timestamp.compareTo(startLdt) >= 0)
                        .filter(e -> endLdt == null || e.timestamp.compareTo(endLdt) <= 0)
                        .toList();

                total = matched.size();
                List<LogEntry> limited = matched.stream()
                        .skip(Math.max(0, matched.size() - limit))
                        .toList();

                // 带 Z 后缀的 ISO-8601 UTC 格式，匹配 API 文档示例 "2026-10-15T10:23:45.123Z"
                DateTimeFormatter outFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                for (LogEntry e : limited) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("timestamp", e.timestamp.format(outFmt));
                    entry.put("level", e.level);
                    entry.put("trace_id", e.traceId);
                    entry.put("module", e.module);
                    entry.put("message", extractMessageBody(e.raw));
                    items.add(entry);
                }
            } catch (IOException e) {
                log.warn("log file read failed: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    // ====================================================================
    // 私有辅助
    // ====================================================================

    /** 解析查询参数时间：支持 ISO-8601 和 Spring Boot 格式 */
    private LocalDateTime parseQueryTime(String t) {
        if (t == null || t.isBlank()) return null;
        try {
            // 先尝试 ISO-8601
            return ZonedDateTime.parse(t, ISO_FMT).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 再尝试 Spring Boot 格式
            try {
                return LocalDateTime.parse(t, BOOT_LOG_FMT);
            } catch (DateTimeParseException e) {
                log.warn("invalid time param: {}", t);
                return null;
            }
        }
    }

    /** 解析单条日志行，返回结构化条目（无法解析则返回 null） */
    private LogEntry parseLogLine(String line) {
        Matcher ts = TS_PATTERN.matcher(line);
        if (!ts.find()) return null;

        LocalDateTime timestamp;
        try {
            timestamp = LocalDateTime.parse(ts.group(1), BOOT_LOG_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }

        String level = extractLevel(line);
        String traceId = extractTraceId(line);
        String module = extractModule(line);

        LogEntry e = new LogEntry();
        e.timestamp = timestamp;
        e.level = level;
        e.traceId = traceId;
        e.module = module;
        e.raw = line;
        return e;
    }

    private String extractLevel(String line) {
        for (String l : new String[]{"ERROR", "WARN", "INFO", "DEBUG"}) {
            // Spring Boot 默认格式: "2026-... ERROR [module] c.p.Class - msg"
            int idx = line.indexOf(" " + l + " ");
            if (idx >= 0) {
                return l;
            }
            // 可能在行首: "ERROR ..."
            if (line.startsWith(l + " ")) {
                return l;
            }
        }
        return "INFO";
    }

    private String extractTraceId(String line) {
        Matcher m = TRACE_PATTERN.matcher(line);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractModule(String line) {
        Matcher m = MODULE_PATTERN.matcher(line);
        if (m.find()) {
            // 优先取 [module] 形式
            if (m.group(1) != null) return m.group(1);
            return m.group(2).trim();
        }
        return null;
    }

    /** 从原始日志行中提取消息正文：Spring Boot 默认格式中，消息在 " - " 分隔符之后 */
    private String extractMessageBody(String rawLine) {
        // Spring Boot 默认: "2026-10-15 ... INFO [module] c.p.Class - message body here"
        int idx = rawLine.indexOf(" - ");
        if (idx >= 0) {
            return rawLine.substring(idx + 3).trim();
        }
        // 备选：有些日志框架用 "| " 或 ": "
        for (String sep : new String[]{"| ", ": ", " -> "}) {
            int i = rawLine.lastIndexOf(sep);
            if (i >= 0) return rawLine.substring(i + sep.length()).trim();
        }
        // 都没匹配到就返回原始行
        return rawLine;
    }

    /** 日志行内部结构 */
    private static class LogEntry {
        LocalDateTime timestamp;
        String level;
        String traceId;
        String module;
        String raw;
    }
}
