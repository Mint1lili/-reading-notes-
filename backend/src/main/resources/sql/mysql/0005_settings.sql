-- ============================================================================
-- Migration 0005: System Settings (MySQL)
-- 用于持久化模型参数 / RAG 参数等设置项（F5 设置页面热更新后重启不丢失）
-- ============================================================================

CREATE TABLE IF NOT EXISTS settings (
    setting_key   VARCHAR(128) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    description   VARCHAR(256),
    updated_at    VARCHAR(64)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- setting_key 已为主键（自带索引），无需额外索引

-- ---------- 记录本次迁移 ----------
INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (5, 'settings', '2026-10-23T00:00:00.000Z');
