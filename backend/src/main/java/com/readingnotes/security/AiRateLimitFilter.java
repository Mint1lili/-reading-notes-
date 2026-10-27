package com.readingnotes.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 接口请求级限流（深层缺陷⑩）：/api/v1/ai/** 是 LLM 成本大户，
 * 登录接口有"5 次锁定"，AI 接口此前无限流——误触/恶意请求可直接刷爆豆包配额。
 *
 * 策略（按方法区分，Redis 分钟窗口）：
 *  - POST（提交/重新生成/SSE 启动）→ 30 次/分钟/用户（每个都是真实 LLM 调用）
 *  - GET（任务轮询）→ 300 次/分钟/用户（前端轮询 1.5s 一次，90s 任务约 60 次轮询）
 * 超限返回 429 + 信封 70010，不进入业务逻辑。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)   // 必须在 AuthFilter(+1) 之后：限流依赖 AuthContext.userId
public class AiRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "rate:ai:";
    private static final int POST_LIMIT = 30;
    private static final int GET_LIMIT = 300;
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AiRateLimitFilter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/v1/ai/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            chain.doFilter(req, resp);   // 未登录由 AuthFilter 处理，这里不拦
            return;
        }
        boolean post = "POST".equalsIgnoreCase(req.getMethod());
        int limit = post ? POST_LIMIT : GET_LIMIT;
        String key = PREFIX + userId + ":" + LocalDateTime.now().format(WINDOW);

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, java.time.Duration.ofSeconds(60));
        }
        if (count != null && count > limit) {
            resp.setStatus(429);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(mapper.writeValueAsString(
                    Result.fail(ErrorCode.AI_RATE_LIMITED.getCode(), ErrorCode.AI_RATE_LIMITED.getMessage())));
            log.warn("ai rate limited user={} method={} uri={} count={}", userId, req.getMethod(),
                    req.getRequestURI(), count);
            return;
        }
        chain.doFilter(req, resp);
    }
}
