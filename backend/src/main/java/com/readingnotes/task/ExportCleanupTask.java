package com.readingnotes.task;

import com.readingnotes.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * 导出文件过期清理（整改计划项 ⑧）：exports/ 目录只增不删，
 * 这里按 app.export-ttl-hours 定期删除过期的 export_*.zip。
 * <p>只清理本系统生成的导出文件（export_*.zip 前缀 + .zip 后缀），
 * 不动目录里的其他文件；目录不存在或为空时直接跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportCleanupTask {

    private final AppProperties props;

    /** 每小时第 17 分钟执行一次（与导出 TTL 的小时粒度匹配） */
    @Scheduled(cron = "0 17 * * * ?")
    public void cleanup() {
        Path dir;
        try {
            dir = Paths.get(props.getExportsDir());
            if (!Files.isDirectory(dir)) {
                return; // 目录尚未创建 → 无事可做
            }
        } catch (Exception e) {
            log.warn("export cleanup: exports dir invalid, skip. {}", e.getMessage());
            return;
        }

        // TTL 至少 1 小时，避免误配为 0 导致新文件被立即删除
        Duration ttl = Duration.ofHours(Math.max(props.getExportTtlHours(), 1));
        Instant cutoff = Instant.now().minus(ttl);

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isExportFile)
                    .filter(p -> isOlderThan(p, cutoff))
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("export cleanup: list exports dir failed. {}", e.getMessage());
        }
    }

    /** 只清理系统生成的导出文件（export_<stamp>.zip），不误删其他文件 */
    private boolean isExportFile(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith("export_") && name.endsWith(".zip");
    }

    private boolean isOlderThan(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            // 读不到修改时间（文件被占用/权限）→ 保守不删
            log.warn("export cleanup: read mtime failed, keep {}. {}", p.getFileName(), e.getMessage());
            return false;
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            log.info("export cleanup: removed {}", p.getFileName());
        } catch (IOException e) {
            log.warn("export cleanup: delete failed {}. {}", p, e.getMessage());
        }
    }
}
