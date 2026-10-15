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
 * 全链路 trace 过滤器（需求 §6.4.2）
 * 响应头统一带 X-Trace-Id；客户端传入则沿用。
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TraceContext.use(request.getHeader("X-Trace-Id"));
            if (TraceContext.current() == null) {
                TraceContext.newTrace();
            }
            response.setHeader("X-Trace-Id", TraceContext.current());
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
