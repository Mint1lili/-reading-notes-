package com.readingnotes.config;

import com.readingnotes.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路 trace 过滤器（需求 §6.4.2）。
 * <p>
 * 每个请求进来先沿用客户端传来的 X-Trace-Id，没有则新建，并回写响应头，
 * 请求结束时清理 MDC，避免 Tomcat 线程复用导致 trace 串号。
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // 仅当客户端显式传入时才沿用；否则下面 current() 会自动新建
            TraceContext.use(request.getHeader("X-Trace-Id"));
            String traceId = TraceContext.current();
            response.setHeader("X-Trace-Id", traceId);
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
