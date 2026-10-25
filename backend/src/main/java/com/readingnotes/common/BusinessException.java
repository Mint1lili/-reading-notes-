package com.readingnotes.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码与前端展示文案，由 {@link GlobalExceptionHandler} 统一转 HTTP 响应。
 * <p>
 * 约定：{@code code} 必须是 {@link ErrorCode} 中已登记的码值，前端按 code 做分支；
 * {@code httpStatus} 与 code 的分组保持一致（如 4xxxx 多为 4xx/5xx），不要只改其中一个。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，与 ErrorCode 对应 */
    private final int code;

    /** 期望返回的 HTTP 状态码 */
    private final int httpStatus;

    /**
     * 使用错误码自带的文案。
     *
     * @param errorCode 预定义错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 使用错误码，但覆盖文案（用于补足上下文，如具体页数、文件名）。
     *
     * @param errorCode 预定义错误码
     * @param message   覆盖后的用户可见文案
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 完全自定义码值与状态（用于 ErrorCode 尚未登记的临时场景，应尽量避免）。
     *
     * @param code       业务错误码
     * @param httpStatus HTTP 状态码
     * @param message    用户可见文案
     */
    public BusinessException(int code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
