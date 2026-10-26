package com.readingnotes.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类（认证新增，借鉴 xiangmu4 的 JwtUtils 精简）。
 *
 * <p>职责：
 * <ul>
 *   <li>生成 Token：把 userId、email 写入 claims，HMAC-SHA256 签名，带过期时间。</li>
 *   <li>解析 Token：校验签名与有效期，提取 userId。</li>
 * </ul>
 *
 * <p>密钥与过期时间从 application.yml 读取（jwt.secret / jwt.expire-millis），
 * 密钥至少 32 字节，启动时校验。</p>
 */
@Slf4j
@Component
public class JwtUtils {

    /** 签名密钥，从配置读取（jwt.secret / 环境变量 JWT_SECRET 注入），至少 32 字节（HMAC-SHA256 要求） */
    @Value("${jwt.secret}")
    private String secret;

    /** Token 有效期（毫秒），默认 2 小时 */
    @Value("${jwt.expire-millis:7200000}")
    private long expireMillis;

    /** 签发者（iss） */
    @Value("${jwt.issuer:reading-notes}")
    private String issuer;

    /**
     * 生成 JWT Token。
     *
     * @param userId 用户主键
     * @param email  登录邮箱（写进 claims，供前端展示）
     * @return 签名后的 Token 字符串
     */
    public String generateToken(Long userId, String email) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put("userId", userId);
        claims.put("email", email);
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMillis);
        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setIssuer(issuer)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Token 并返回 claims；签名错误 / 过期 / 格式非法时抛 JwtException 子类。
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token 不能为空");
        }
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** 从 Token 提取 userId；Token 为空或解析失败返回 null（不抛异常） */
    public Long getUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object userId = parseToken(token).get("userId");
            if (userId instanceof Number) {
                return ((Number) userId).longValue();
            }
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 校验 Token 是否有效（签名正确且未过期） */
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Token 已过期: {}", e.getMessage());
            return false;
        } catch (SignatureException | MalformedJwtException
                 | UnsupportedJwtException | IllegalArgumentException e) {
            log.warn("Token 无效: {}", e.getMessage());
            return false;
        }
    }

    /** 构造签名密钥（HMAC-SHA256 要求至少 32 字节） */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 启动时校验密钥长度，不足 32 字节直接阻止启动，避免弱密钥 */
    @PostConstruct
    public void validateSecret() {
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足 32 字节（当前 " + length + " 字节）。"
                    + "请在 application.yml 中配置 jwt.secret 为至少 32 字节的随机字符串。");
        }
    }
}
