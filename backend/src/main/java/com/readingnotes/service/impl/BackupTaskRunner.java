package com.readingnotes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.config.AppProperties;
import com.readingnotes.repository.BackupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份异步执行器 — 独立 Bean 解决 @Async 自调用失效问题
 * 被 SettingsServiceImpl 通过代理调用，Spring AOP 才能拦截 @Async
 */
@Slf4j
@Component
public class BackupTaskRunner {

    private final AppProperties appProperties;
    private final BackupRepository backupRepo;

    public BackupTaskRunner(AppProperties appProperties,
                            BackupRepository backupRepo,
                            ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.backupRepo = backupRepo;
    }

    /** 异步执行备份：SQLite + uploads/ + prompts/ → zip → SHA-256 */
    @Async
    public void doBackup(String backupId, String targetPath) {
        String finishedAt = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
        try {
            Path zipPath = Paths.get(targetPath);
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                // 1. 打包 uploads 目录
                addDirectoryToZip(zos, appProperties.getUploadsDir(), "uploads/");
                // 2. 打包 prompts 目录
                addDirectoryToZip(zos, appProperties.getPromptsDir(), "prompts/");
                // 3. 打包 SQLite 数据库文件（找 data 目录下的 .db 文件）
                String dataDir = appProperties.getDataDir();
                if (dataDir != null) {
                    addDbFilesToZip(zos, dataDir);
                }
                // 4. Chroma snapshot（如果配置了 embedded 数据目录则打包；server 模式跳过）
                if (appProperties.getChromaDir() != null && !appProperties.getChromaDir().isBlank()) {
                    addDirectoryToZip(zos, appProperties.getChromaDir(), "chroma/");
                }
            }

            // 计算 SHA-256
            String sha256 = sha256OfFile(zipPath.toFile());
            long fileSize = Files.size(zipPath);

            backupRepo.updateStatus(backupId, "SUCCESS", finishedAt, fileSize, sha256, null);
            log.info("backup success: id={}, size={}, sha256={}", backupId, fileSize, sha256);

        } catch (Exception e) {
            log.error("backup failed: id={}", backupId, e);
            backupRepo.updateStatus(backupId, "FAILED", finishedAt, 0, "", e.getMessage());
        }
    }

    private void addDirectoryToZip(ZipOutputStream zos, String dirPath, String zipPrefix) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return;
        try (Stream<Path> walker = Files.walk(dir.toPath())) {
            walker.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = dir.toPath().relativize(p).toString().replace("\\", "/");
                    ZipEntry entry = new ZipEntry(zipPrefix + rel);
                    zos.putNextEntry(entry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private void addDbFilesToZip(ZipOutputStream zos, String dataDir) throws IOException {
        File dir = new File(dataDir);
        if (!dir.exists()) return;
        File[] dbFiles = dir.listFiles((d, name) -> name.endsWith(".db"));
        if (dbFiles == null) return;
        for (File f : dbFiles) {
            ZipEntry entry = new ZipEntry("sqlite/" + f.getName());
            zos.putNextEntry(entry);
            try (FileInputStream fis = new FileInputStream(f)) {
                fis.transferTo(zos);
            }
            zos.closeEntry();
        }
    }

    /** 暴露给 SettingsServiceImpl 用于恢复前校验 */
    public String sha256OfFile(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("sha256 compute failed", e);
            return "";
        }
    }
}
