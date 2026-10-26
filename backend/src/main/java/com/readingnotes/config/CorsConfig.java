package com.readingnotes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 本地开发 CORS：前端 Vite（5173）→ 后端（8080）。
 * <p>
 * 只放行 localhost / 127.0.0.1 两种写法（浏览器对二者的 Origin 判定不同，必须都登记）；
 * 不开启 credentials，因为当前鉴权不依赖 Cookie。生产部署若前后端同域，可直接移除此配置。
 */
@Configuration
public class CorsConfig {

    /**
     * 注册全局 CORS 过滤器。
     *
     * @return 对所有路径生效的 CorsFilter
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 本地开发放开跨域：Vite(5173) + 任意本地来源（含 file:// 测试页、其他前端端口）
        cfg.addAllowedOrigin("http://localhost:5173");
        cfg.addAllowedOrigin("http://127.0.0.1:5173");
        cfg.addAllowedOriginPattern("*");
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(source);
    }
}
