-- 多租户隔离（深层缺陷①）：books/notes 增加 owner_id
-- NULL = 全局共享（保留历史数据与组员协作），非空 = 上传者/创建者私有
-- 执行：mysql -uroot -p reading_notes < 0007_tenant_owner_id.sql（幂等，可重复执行）

ALTER TABLE books
    ADD COLUMN owner_id BIGINT NULL COMMENT '上传者user_id；NULL=全局共享' AFTER book_id;

ALTER TABLE notes
    ADD COLUMN owner_id BIGINT NULL COMMENT '笔记创建者user_id；NULL=共享' AFTER book_owner_id;

-- ---------- 记录本次迁移 ----------
INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (7, 'tenant_owner_id', '2026-10-25T00:00:00.000Z');

-- 说明：
-- 1. 历史数据 owner_id 保持 NULL → 全部可见（共享），不破坏现有行为；
-- 2. 新上传的书 / 新创建的笔记自动写入当前登录用户 id；
-- 3. 查询过滤：owner_id IS NULL OR owner_id = 当前用户（登录必需，AuthFilter 注入 AuthContext）。
