-- ============================================================
-- 阅读笔记系统 · 用户认证表（邮箱验证码注册/登录）
-- 说明：在现有 reading_notes 库上执行本脚本即可（幂等，可重复执行）
-- 用法：Navicat 连接 reading_notes 库 → 新建查询 → 运行本文件
-- 字段说明：
--   email         邮箱，全局唯一，登录标识
--   password_hash BCrypt 密码哈希；验证码注册阶段为 NULL，设置密码后填充
--   status        1 正常 / 0 冻结
-- ============================================================
USE reading_notes;

CREATE TABLE IF NOT EXISTS users (
    user_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(128) NOT NULL COMMENT '邮箱（全局唯一，登录标识）',
    password_hash VARCHAR(255) DEFAULT NULL COMMENT 'BCrypt 密码哈希（未设置密码时为 NULL）',
    nickname      VARCHAR(64)  DEFAULT NULL COMMENT '昵称（可选）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1正常 0冻结',
    created_at    VARCHAR(32)  NOT NULL COMMENT '创建时间（ISO 格式）',
    updated_at    VARCHAR(32)  NOT NULL COMMENT '更新时间（ISO 格式）',
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
