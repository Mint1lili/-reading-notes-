package com.readingnotes.security;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;

/**
 * 当前请求登录用户上下文（ThreadLocal）。
 *
 * <p>由 {@link AuthFilter} 在校验通过后写入 userId，业务层（Controller/Service）
 * 通过 {@link #getUserId()} 读取；请求结束时由 Filter 统一清理，
 * 避免 Tomcat 线程复用导致用户串号。
 */
public final class AuthContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 设置当前请求用户 */
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /** 获取当前请求用户；未登录（Filter 未写入）返回 null */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 获取当前请求用户；未登录直接抛 80008（供需要用户维度的业务使用） */
    public static Long requireUserId() {
        Long id = USER_ID.get();
        if (id == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return id;
    }

    /** 清理当前线程用户，必须由 Filter 在 finally 中调用 */
    public static void clear() {
        USER_ID.remove();
    }
}
