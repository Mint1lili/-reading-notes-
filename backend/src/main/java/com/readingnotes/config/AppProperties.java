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
    private String sqlitePath;
    private String uploadsDir;
    private String logsDir;
    private String backupsDir;
    private String promptsDir;
}
