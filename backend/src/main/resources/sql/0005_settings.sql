-- ============================================================================
-- Migration 0005: System Settings (key-value)
-- 用于持久化模型参数 / RAG 参数等设置项（F5 设置页面热更新后重启不丢失）
-- ============================================================================

CREATE TABLE IF NOT EXISTS settings (
    setting_key   TEXT    PRIMARY KEY,
    setting_value TEXT    NOT NULL,
    description   TEXT,
    updated_at    TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_settings_key ON settings(setting_key);

-- ---------- 记录本次迁移 ----------
INSERT OR IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (5, 'settings', '2026-10-23T00:00:00.000Z');
