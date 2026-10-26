-- ============================================================================
-- 0006 修复 ai_versions 多版本唯一约束（MySQL）
-- 问题：idx_aiver_target_active 在 (target_type, target_id, artifact_type) 上无条件唯一，
--       导致同一本书只能存在一行 AI 产物（无论 is_active），多版本生成必报
--       Duplicate entry ... for key 'idx_aiver_target_active'。
-- 修复：删除该索引。版本唯一性已由表内 UNIQUE (target_type, target_id, artifact_type,
--       version_number) 保证；同一时刻仅一条 is_active=1 由代码逻辑（先置 0 后插 1）维护。
-- 适用：MySQL 8（sqlite 版 0001_init.sql 同问题，如使用 sqlite 需一并处理）。
-- ============================================================================
ALTER TABLE ai_versions DROP INDEX idx_aiver_target_active;
