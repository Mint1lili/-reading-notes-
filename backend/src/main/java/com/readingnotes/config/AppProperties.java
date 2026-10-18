package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用存储路径配置（application.yml 的 app.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dataDir;
    private String uploadsDir;
    private String logsDir;
    private String backupsDir;
    private String promptsDir;

    /** 笔记导出产物目录（API §9.11 下载路由读取此目录） */
    private String exportsDir;

    /** 导出文件有效期（小时），用于响应里的 expires_at；到期文件由清理任务删除 */
    private int exportTtlHours = 1;
}
