package com.readingnotes.task;

import com.readingnotes.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExportCleanupTask 单元测试：用真实临时目录验证
 * ① 超过 TTL 的 export_*.zip 被删除；② 未过期的不删；
 * ③ 非导出文件不删；④ 目录不存在时不抛异常。
 */
class ExportCleanupTaskTest {

    @TempDir
    Path tempDir;

    private ExportCleanupTask newTask() {
        AppProperties props = new AppProperties();
        props.setExportsDir(tempDir.toString());
        props.setExportTtlHours(1);
        return new ExportCleanupTask(props);
    }

    private Path write(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "test");
        return p;
    }

    @Test
    @DisplayName("清理：过期 export_.zip 删除，未过期与非导出文件保留")
    void cleanup_removesOnlyExpiredExports() throws IOException {
        Path old = write("export_20200101000000.zip");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(7200))); // 2 小时前 → 过期
        Path fresh = write("export_20990101000000.zip");                                // 当前时间 → 保留
        Path foreign = write("keep_me.txt");                                            // 非导出文件 → 保留

        newTask().cleanup();

        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    @DisplayName("清理：目录不存在时静默跳过，不抛异常")
    void cleanup_missingDir_noError() {
        AppProperties props = new AppProperties();
        props.setExportsDir(tempDir.resolve("no_such_dir").toString());
        props.setExportTtlHours(1);
        new ExportCleanupTask(props).cleanup(); // 不应抛异常
    }

    @Test
    @DisplayName("清理：空目录直接跳过")
    void cleanup_emptyDir_noError() {
        newTask().cleanup(); // 不应抛异常
    }
}
