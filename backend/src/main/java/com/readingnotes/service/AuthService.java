package com.readingnotes.service;

import com.readingnotes.dto.TokenVO;
import com.readingnotes.entity.User;

/**
 * 认证服务：邮箱验证码注册/登录、密码登录、设置密码。
 */
public interface AuthService {

    /**
     * 发送邮箱验证码（60 秒限频，5 分钟有效）。
     *
     * @param email 收件邮箱
     * @param scene 场景：register / login / set_password
     */
    void sendCaptcha(String email, String scene);

    /** 邮箱 + 验证码注册，注册即登录，返回 token */
    TokenVO register(String email, String code);

    /** 邮箱 + 验证码登录，返回 token */
    TokenVO loginByCode(String email, String code);

    /** 邮箱 + 密码登录，返回 token */
    TokenVO loginByPassword(String email, String password);

    /** 设置密码（需 set_password 场景验证码），成功后可用邮箱 + 密码登录 */
    void setPassword(String email, String code, String password);

    /** 按主键查用户（脱敏，不含密码哈希），用于 /auth/me */
    User getCurrentUser(Long userId);
}
