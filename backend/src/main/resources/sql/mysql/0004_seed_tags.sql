-- ============================================================================
-- Migration 0004: Seed System Tags
-- 写入 6 个预置系统标签
-- ============================================================================

INSERT IGNORE INTO tags (tag_id, name, color, created_at) VALUES
('tag_high_freq', '高频考点',  '#E6A23C', '2026-10-15T00:00:00.000Z'),
('tag_book_club', '读书会',    '#409EFF', '2026-10-15T00:00:00.000Z'),
('tag_golden',    '金句',      '#67C23A', '2026-10-15T00:00:00.000Z'),
('tag_review',    '待复习',    '#F56C6C', '2026-10-15T00:00:00.000Z'),
('tag_classic',   '经典',      '#909399', '2026-10-15T00:00:00.000Z'),
('tag_personal',  '个人总结',  '#9C27B0', '2026-10-15T00:00:00.000Z');

INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (4, 'seed_tags', '2026-10-15T00:00:00.000Z');

SELECT * FROM tags ORDER BY tag_id;
