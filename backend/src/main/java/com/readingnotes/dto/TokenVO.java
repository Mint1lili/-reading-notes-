package com.readingnotes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 登录成功返回的令牌信息。
 */
@Data
public class TokenVO {

    /** JWT access token */
    private String token;

    /** 令牌类型，固定 Bearer */
    @JsonProperty("token_type")
    private String tokenType;

    /** 有效期（秒） */
    @JsonProperty("expires_in")
    private long expiresIn;

    /** 用户主键 */
    @JsonProperty("user_id")
    private Long userId;

    /** 登录邮箱 */
    private String email;

    public TokenVO(String token, String tokenType, long expiresIn, Long userId, String email) {
        this.token = token;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.email = email;
    }
}
