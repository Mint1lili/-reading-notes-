package com.readingnotes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.BuildProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 分片上传会话管理（需求 F1.1 边界：上传断网时支持断点续传）。
 *
 * <h3>为什么需要它</h3>
 * 单请求 multipart 在 50MB 文件 + 家宽上行时很容易中断，一断就得从头再传；比赛现场
 * 网络更不可控。分片上传把文件切成 5MB 片，前端记录 {@code uploadId}，断线后只补缺失片。
 *
 * <h3>为什么不用数据库存会话</h3>
 * 会话是"临时态"，生命周期 ≤24 小时，且与书籍记录无关（上传成功才产生 book_id）。
 * 落一个 JSON 文件到 {@code uploads/.sessions/} 即可跨进程重启恢复，避免为临时数据
 * 增加一张表与一次迁移。
 *
 * <h3>目录布局</h3>
 * <pre>
 * uploads/.sessions/{uploadId}.json     会话元数据（文件名/大小/分片数/已收片号）
 * uploads/.chunks/{uploadId}/{index}.part  分片数据
 * uploads/.tmp/{uploadId}.pdf           合并后的临时文件（交给 BookService 入库后删除）
 * </pre>
 */
@Slf4j
@Service
public class UploadSessionService {

    private final AppProperties appProps;
    private final BuildProperties buildProps;
    private final ObjectMapper mapper = new ObjectMapper();

    public UploadSessionService(AppProperties appProps, BuildProperties buildProps) {
        this.appProps = appProps;
        this.buildProps = buildProps;
    }

    // =========================================================================
    // 会话模型
    // =========================================================================

    @Data
    public static class Session {
        private String uploadId;
        private String fileName;
        private long fileSize;
        private long chunkSize;
        private int totalChunks;
        /** 已收到的分片下标 */
        private List<Integer> received = new ArrayList<>();
        private String createdAt;
        private String expiresAt;
    }

    /** 上传会话 ID 白名单：up_ + 32 位 hex（服务端生成，杜绝路径穿越） */
    private static final Pattern UPLOAD_ID_PATTERN =
            Pattern.compile("^up_[a-f0-9]{32}$");

    /**
     * 初始化上传会话。
     * <p>
     * 同名文件重复 init 时，若大小一致则复用未过期的会话（前端刷新/重连场景），
     * 避免每次都新建目录、丢进度。
     */
    public Map<String, Object> init(String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少文件名");
        }
        String name = Path.of(fileName).getFileName().toString();
        if (!name.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.NOT_PDF);
        }
        if (fileSize <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件大小不合法");
        }
        if (fileSize > 50L * 1024 * 1024) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        long chunkSize = Math.max(1, buildProps.getUploadChunkSize());
        int total = (int) ((fileSize + chunkSize - 1) / chunkSize);

        Session s = findReusable(name, fileSize, chunkSize);
        if (s == null) {
            s = new Session();
            s.setUploadId("up_" + UUID.randomUUID().toString().replace("-", ""));
            s.setFileName(name);
            s.setFileSize(fileSize);
            s.setChunkSize(chunkSize);
            s.setTotalChunks(total);
            s.setCreatedAt(Instant.now().toString());
            s.setExpiresAt(Instant.now()
                    .plus(buildProps.getUploadSessionTtlHours(), ChronoUnit.HOURS).toString());
            save(s);
            log.info("upload session created id={} file={} size={} chunks={}",
                    s.getUploadId(), name, fileSize, total);
        }
        return view(s);
    }

    /** 上传单片（幂等：同一下标重复上传覆盖旧片） */
    public Map<String, Object> saveChunk(String uploadId, int index, MultipartFile chunk) {
        assertUploadId(uploadId);
        Session s = load(uploadId);
        if (index < 0 || index >= s.getTotalChunks()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "分片序号越界（有效范围 0~" + (s.getTotalChunks() - 1) + "）");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分片内容为空");
        }
        // 最后一片可能小于 chunkSize；其余片必须不超过约定大小
        long expectMax = s.getChunkSize();
        if (chunk.getSize() > expectMax) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分片大小超过约定值");
        }
        Path dir = chunkDir(uploadId);
        try {
            Files.createDirectories(dir);
            Path part = dir.resolve(index + ".part");
            Path tmp = dir.resolve(index + ".part.tmp");
            try (InputStream in = chunk.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            synchronized (s) {
                if (!s.getReceived().contains(index)) {
                    s.getReceived().add(index);
                }
            }
            save(s);
        } catch (IOException e) {
            log.error("save chunk failed uploadId={} index={}", uploadId, index, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分片保存失败，请检查磁盘空间");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("upload_id", uploadId);
        data.put("index", index);
        data.put("received", s.getReceived().size());
        data.put("total_chunks", s.getTotalChunks());
        data.put("progress", percent(s));
        return data;
    }

    /** 查询会话（断线重连时前端据此只补缺失片） */
    public Map<String, Object> status(String uploadId) {
        assertUploadId(uploadId);
        Session s = load(uploadId);
        if (expired(s)) {
            cleanup(uploadId);
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        return view(s);
    }

    /** 读取有效会话（合并入库时取原始文件名/声明大小，避免前端重复传） */
    public Session require(String uploadId) {
        assertUploadId(uploadId);
        Session s = load(uploadId);
        if (expired(s)) {
            cleanup(uploadId);
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        return s;
    }

    /**
     * 合并分片，返回临时 PDF 路径。调用方（BookService）负责落库并删除临时文件。
     * <p>
     * 合并前校验：分片齐全 + 合并后字节数 == 声明的 fileSize。少一片就报错而不是
     * 拼出一个"能打开但内容缺失"的坏 PDF —— 后者会在构建阶段才炸，排查成本高得多。
     */
    public Path assemble(String uploadId) {
        assertUploadId(uploadId);
        Session s = load(uploadId);
        if (expired(s)) {
            cleanup(uploadId);
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        if (s.getReceived().size() < s.getTotalChunks()) {
            List<Integer> missing = missing(s);
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "上传未完成，还缺 " + missing.size() + " 个分片，请续传后再提交");
        }
        Path tmpDir = tmpDir();
        Path out = tmpDir.resolve(uploadId + ".pdf");
        try {
            Files.createDirectories(tmpDir);
            try (OutputStream os = Files.newOutputStream(out)) {
                for (int i = 0; i < s.getTotalChunks(); i++) {
                    Path part = chunkDir(uploadId).resolve(i + ".part");
                    if (!Files.isRegularFile(part)) {
                        throw new BusinessException(ErrorCode.PARAM_ERROR,
                                "分片 " + i + " 缺失，请重新上传该片");
                    }
                    Files.copy(part, os);
                }
            }
            long actual = Files.size(out);
            if (actual != s.getFileSize()) {
                Files.deleteIfExists(out);
                // 大小不符说明客户端声明有误，分片已无复用价值：立刻清掉会话，
                // 避免残留到次日 04:10 的兜底清理（否则用户重传时还会命中旧会话）
                cleanup(uploadId);
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "文件大小不一致（期望 " + s.getFileSize() + " 字节，实际 " + actual + " 字节），请重新上传");
            }
            log.info("upload assembled id={} file={} size={}", uploadId, s.getFileName(), actual);
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("assemble failed uploadId={}", uploadId, e);
            deleteQuietly(out);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "合并分片失败，请重试");
        }
    }

    /** 上传成功后清理分片与会话（临时 PDF 由调用方删除） */
    public void complete(String uploadId) {
        assertUploadId(uploadId);
        cleanup(uploadId);
    }

    /** 主动放弃上传 */
    public void abort(String uploadId) {
        assertUploadId(uploadId);
        cleanup(uploadId);
        log.info("upload aborted id={}", uploadId);
    }

    /** uploadId 格式白名单校验：非服务端生成格式一律按会话不存在处理（防路径穿越） */
    private void assertUploadId(String uploadId) {
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
    }

    // =========================================================================
    // 视图与工具
    // =========================================================================

    /** 会话视图：字段名直接对应 API 文档 §5.1 的分片上传响应 */
    private Map<String, Object> view(Session s) {
        List<Integer> missing = missing(s);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("upload_id", s.getUploadId());
        data.put("file_name", s.getFileName());
        data.put("file_size", s.getFileSize());
        data.put("chunk_size", s.getChunkSize());
        data.put("total_chunks", s.getTotalChunks());
        data.put("received", s.getReceived());
        data.put("missing", missing);
        data.put("progress", percent(s));
        data.put("complete", missing.isEmpty());
        data.put("expires_at", s.getExpiresAt());
        return data;
    }

    /** 按已收片数算进度；片数为 0 时返回 0，避免除零 */
    private static int percent(Session s) {
        if (s.getTotalChunks() <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(s.getReceived().size() * 100.0 / s.getTotalChunks()));
    }

    /**
     * 计算缺失片号（升序）。
     * <p>
     * 用"遍历 0..total-1 判断是否在 received 集合"而不是集合差集，
     * 是为了让前端拿到的顺序天然可用于逐个补传。
     */
    private List<Integer> missing(Session s) {
        List<Integer> miss = new ArrayList<>();
        for (int i = 0; i < s.getTotalChunks(); i++) {
            if (!s.getReceived().contains(i)) {
                miss.add(i);
            }
        }
        return miss;
    }

    /**
     * 查找可复用的未过期会话：文件名、大小、分片大小三者完全一致才算同一个文件。
     * <p>
     * 三者缺一都会导致已上传分片与新文件错位，因此不做宽松匹配；
     * 命中多个时取最近创建的那个。
     */
    private Session findReusable(String fileName, long fileSize, long chunkSize) {
        Path dir = sessionDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::readQuietly)
                    .filter(s -> s != null && !expired(s))
                    .filter(s -> fileName.equals(s.getFileName())
                            && s.getFileSize() == fileSize
                            && s.getChunkSize() == chunkSize)
                    .max(Comparator.comparing(Session::getCreatedAt))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 会话是否过期；时间解析失败按已过期处理（宁可让用户重传，也不复用脏会话） */
    private boolean expired(Session s) {
        try {
            return Instant.parse(s.getExpiresAt()).isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    /** 会话落盘为 json（不依赖 DB，服务重启后仍可续传） */
    private void save(Session s) {
        try {
            Files.createDirectories(sessionDir());
            Path p = sessionDir().resolve(s.getUploadId() + ".json");
            mapper.writeValue(p.toFile(), s);
        } catch (IOException e) {
            log.error("save upload session failed id={}", s.getUploadId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上传会话保存失败");
        }
    }

    /** 读取会话；不存在/损坏一律抛 410（UPLOAD_SESSION_GONE），前端据此重新 init */
    private Session load(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        Path p = sessionDir().resolve(uploadId + ".json");
        if (!Files.isRegularFile(p)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        Session s = readQuietly(p);
        if (s == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_GONE);
        }
        return s;
    }

    /** 读会话文件，损坏时返回 null 而不是抛异常（清理任务需要跳过坏文件继续扫） */
    private Session readQuietly(Path p) {
        try {
            return mapper.readValue(p.toFile(), Session.class);
        } catch (IOException e) {
            log.warn("read upload session failed file={}", p);
            return null;
        }
    }

    /** 清理过期会话：每天 04:10 扫一遍，避免临时分片长期占盘 */
    @Scheduled(cron = "${build.upload-cleanup-cron:0 10 4 * * ?}")
    public void cleanupExpired() {
        Path dir = sessionDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                Session s = readQuietly(p);
                if (s == null || expired(s)) {
                    cleanup(s == null ? p.getFileName().toString().replace(".json", "") : s.getUploadId());
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("upload session cleanup failed: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("upload session cleanup done, removed={}", removed);
        }
    }

    /** 删除一个会话的全部痕迹：分片目录、会话 json、合并用临时 PDF */
    private void cleanup(String uploadId) {
        deleteRecursively(chunkDir(uploadId));
        deleteQuietly(sessionDir().resolve(uploadId + ".json"));
        deleteQuietly(tmpDir().resolve(uploadId + ".pdf"));
    }

    /** 递归删除目录；逆序保证先删文件再删空目录 */
    private void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("cleanup dir failed {}: {}", dir, e.getMessage());
        }
    }

    /** 删除文件，失败只记日志——清理是尽力而为，不应让主流程失败 */
    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("delete failed path={} {}", p, e.getMessage());
        }
    }

    private Path uploadsRoot() {
        return Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize();
    }

    private Path sessionDir() {
        return uploadsRoot().resolve(".sessions");
    }

    private Path chunkDir(String uploadId) {
        return uploadsRoot().resolve(".chunks").resolve(uploadId);
    }

    private Path tmpDir() {
        return uploadsRoot().resolve(".tmp");
    }
}
