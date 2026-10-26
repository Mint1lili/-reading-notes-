package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.dto.CaptchaRequestDTO;
import com.readingnotes.dto.LoginCodeRequestDTO;
import com.readingnotes.dto.LoginPasswordRequestDTO;
import com.readingnotes.dto.RegisterRequestDTO;
import com.readingnotes.dto.SetPasswordRequestDTO;
import com.readingnotes.dto.TokenVO;
import com.readingnotes.entity.User;
import com.readingnotes.security.AuthContext;
import com.readingnotes.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口（认证新增，路径前缀 /api/v1/auth）。
 *
 * <ul>
 *   <li>POST /api/v1/auth/captcha        发送邮箱验证码（register/login/set_password）</li>
 *   <li>POST /api/v1/auth/register       邮箱 + 验证码注册（注册即登录）</li>
 *   <li>POST /api/v1/auth/login/code     邮箱 + 验证码登录</li>
 *   <li>POST /api/v1/auth/login/password 邮箱 + 密码登录（设置密码后可用）</li>
 *   <li>POST /api/v1/auth/password       设置/重置密码（需验证码）</li>
 *   <li>GET  /api/v1/auth/me             当前登录用户信息（Authorization: Bearer &lt;token&gt;）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 发送邮箱验证码；请求体：{ "email": "xx@xx.com", "scene": "register" } */
    @PostMapping("/captcha")
    public Result<Map<String, Object>> captcha(@Valid @RequestBody CaptchaRequestDTO dto) {
        authService.sendCaptcha(dto.getEmail(), dto.getScene());
        Map<String, Object> data = new HashMap<>(2);
        data.put("ttl_seconds", 300);
        data.put("limit_seconds", 60);
        return Result.ok(data);
    }

    /** 邮箱 + 验证码注册，注册即登录返回 token */
    @PostMapping("/register")
    public Result<TokenVO> register(@Valid @RequestBody RegisterRequestDTO dto) {
        return Result.ok(authService.register(dto.getEmail(), dto.getCode()));
    }

    /** 邮箱 + 验证码登录 */
    @PostMapping("/login/code")
    public Result<TokenVO> loginByCode(@Valid @RequestBody LoginCodeRequestDTO dto) {
        return Result.ok(authService.loginByCode(dto.getEmail(), dto.getCode()));
    }

    /** 邮箱 + 密码登录（需先设置密码） */
    @PostMapping("/login/password")
    public Result<TokenVO> loginByPassword(@Valid @RequestBody LoginPasswordRequestDTO dto) {
        return Result.ok(authService.loginByPassword(dto.getEmail(), dto.getPassword()));
    }

    /** 设置/重置密码；请求体：{ "email": ..., "code": ..., "password": ... } */
    @PostMapping("/password")
    public Result<Void> setPassword(@Valid @RequestBody SetPasswordRequestDTO dto) {
        authService.setPassword(dto.getEmail(), dto.getCode(), dto.getPassword());
        return Result.ok();
    }

    /** 当前登录用户信息（脱敏，不含密码哈希）；userId 由 AuthFilter 解析并写入 AuthContext */
    @GetMapping("/me")
    public Result<User> me() {
        Long userId = AuthContext.getUserId();
        return Result.ok(authService.getCurrentUser(userId));
    }
}
