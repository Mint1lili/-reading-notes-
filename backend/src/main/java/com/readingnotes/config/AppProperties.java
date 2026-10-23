package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用存储路径配置（application.yml 的 app.*）。
 * <p>
 * 所有路径均支持通过环境变量 {@code APP_DATA_DIR} 整体切换根目录，
 * 便于测试环境与生产环境隔离；未配置时回落到工作目录下的 {@code data/}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 数据根目录：其余子目录默认都挂在它下面 */
    private String dataDir;

    /** 原书 PDF 存放目录，按 bookId 命名，删除书籍时同步清理 */
    private String uploadsDir;

    /** 运行日志目录（app.log / 错误堆栈） */
    private String logsDir;

    /** 备份输出目录（F6 导出与恢复） */
    private String backupsDir;

    /** Prompt 模板与版本快照目录 */
    private String promptsDir;
}
