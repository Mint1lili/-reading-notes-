package com.readingnotes.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;

/**
 * SQLite 数据源（数据库设计 §2 / sql README §5.1）
 * - 连接级 PRAGMA：foreign_keys / WAL / synchronous / busy_timeout 每次连接必须设置
 * - 单用户本地部署，Hikari 池小配比即可
 */
@Configuration
public class SqliteConfig {

    @Bean
    public DataSource sqliteDataSource(AppProperties props) {
        File dbFile = new File(props.getSqlitePath());
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        HikariConfig cfg = new HikariConfig();
        // SQLite JDBC 支持 URL 查询参数承载连接级 PRAGMA（逐连接生效）
        // 注意：多语句分号拼接在 connectionInitSql 中不可用，故 WAL/busy_timeout/synchronous 走 URL 参数，
        //       foreign_keys 作为单条 init SQL 单独执行（数据库设计 §2 / sql README §5.1）
        cfg.setJdbcUrl("jdbc:sqlite:" + props.getSqlitePath()
                + "?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setConnectionInitSql("PRAGMA foreign_keys=ON");
        // 本地单用户：避免频繁连接检查
        cfg.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(cfg);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
