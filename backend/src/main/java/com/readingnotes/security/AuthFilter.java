package com.readingnotes.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import com.readingnotes.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 全局 Token 鉴权过滤器（P0-1 安全底线）。
 *
 * <p>除白名单（认证与健康检查）外，所有 /api/ 请求必须携带
 * {@code Authorization: Bearer <token>}；token 缺失/无效/过期一律 401（80008）。
 * 校验通过后把 userId 写入 {@link AuthContext}，供 Controller/Service 读取。
 *
 * <p>白名单：/api/v1/auth/**（登录/注册/验证码）、/api/v1/health/**（健康检查）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    /**
     * 白名单：仅未登录也能访问的接口（发送验证码/登录/注册/设置密码/健康检查）。
     * 注意：/api/v1/auth/me 不在此列——me 需要登录态，必须经过 Filter 校验并写入 AuthContext，
     * 若按旧前缀 /api/v1/auth 整体放行会导致 me 永远拿不到 userId 而 401。
     */
    private static final String[] WHITELIST_PREFIXES = {
            "/api/v1/auth/captcha",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/password",
            "/api/v1/health"
    };

    private final JwtUtils jwtUtils;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 非 API 请求（静态资源等）一律放行
        if (!uri.startsWith("/api/")) {
            return true;
        }
        for (String prefix : WHITELIST_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            token = authorization.substring(BEARER_PREFIX.length()).trim();
        }

        Long userId = jwtUtils.getUserId(token);
        if (userId == null) {
            log.info("unauthorized access path={} trace={}",
                    request.getRequestURI(), TraceContext.current());
            writeUnauthorized(response);
            return;
        }

        AuthContext.setUserId(userId);
        try {
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    /** 写 401 统一信封（与全局异常处理器同构，前端 http.js 按 401/80008 跳登录） */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> body = Result.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        body.setTraceId(TraceContext.current());
        response.getOutputStream().write(
                mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
