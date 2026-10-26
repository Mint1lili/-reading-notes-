package com.readingnotes.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户实体（users 表，认证新增）
 *
 * <p>邮箱为全局唯一登录标识；passwordHash 仅后端存储与校验使用，
 * 接口返回用户信息时通过 AuthService 脱敏，不下发该字段。</p>
 */
@Data
public class User {

    /** 主键，自增 */
    @JsonProperty("user_id")
    private Long userId;

    /** 邮箱（全局唯一，登录标识） */
    private String email;

    /** BCrypt 密码哈希（验证码注册阶段为 null，设置密码后填充） */
    @JsonProperty("password_hash")
    private String passwordHash;

    /** 昵称（可选） */
    private String nickname;

    /** 账号状态：1 正常 / 0 冻结 */
    private Integer status;

    /** 创建时间（ISO 毫秒格式） */
    @JsonProperty("created_at")
    private String createdAt;

    /** 更新时间（ISO 毫秒格式） */
    @JsonProperty("updated_at")
    private String updatedAt;
}
