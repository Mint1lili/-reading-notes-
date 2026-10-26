package com.readingnotes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CORS：前端 Vite（5173）→ 后端（8080）。
 * <p>
 * 只放行配置的白名单来源（默认 localhost / 127.0.0.1 两种写法，
 * 浏览器对二者的 Origin 判定不同，必须都登记）；不开启 credentials，
 * 因为当前鉴权不依赖 Cookie。生产部署通过 CORS_ALLOWED_ORIGINS 注入前端域名。
 */
@Configuration
public class CorsConfig {

    /** 白名单来源，逗号分隔；生产通过环境变量 CORS_ALLOWED_ORIGINS 注入前端域名 */
    @Value("${app.cors-allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    /**
     * 注册全局 CORS 过滤器。
     *
     * @return 对所有路径生效的 CorsFilter
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (origins.isEmpty()) {
            // 配置为空则不开放跨域（前后端同域部署时无需 CORS）
            return new CorsFilter(new UrlBasedCorsConfigurationSource());
        }
        for (String origin : origins) {
            cfg.addAllowedOrigin(origin);
        }
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(source);
    }
}
