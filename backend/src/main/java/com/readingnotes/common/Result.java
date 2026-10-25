package com.readingnotes.common;

import lombok.Data;

/**
 * 统一响应信封（API 文档 §2.1）：{@code { code, message, data, trace_id }}。
 * <p>
 * 所有接口（含失败）都返回该结构，HTTP 状态码单独表达传输层语义，
 * 前端因此可以只解一层 JSON，无需按状态码猜响应体形状。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 0 = 成功；非 0 见 ErrorCode */
    private int code;

    /** 面向用户的提示文案；成功固定为 "success" */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 全链路追踪 id，与响应头 X-Trace-Id 一致，便于用户报错时定位日志 */
    private String traceId;

    /**
     * 成功响应。
     *
     * @param data 业务数据，可为 null
     * @return 填充了当前 trace_id 的成功信封
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        r.traceId = TraceContext.current();
        return r;
    }

    /** 无数据的成功响应（如删除、取消构建） */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应，trace_id 取当前上下文。
     *
     * @param code    业务错误码
     * @param message 用户可见文案
     */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = TraceContext.current();
        return r;
    }

    /**
     * 失败响应，显式指定 trace_id（用于异步线程等 MDC 已失效的场景）。
     *
     * @param code    业务错误码
     * @param message 用户可见文案
     * @param traceId 原始请求的 trace_id，保证前后端可对齐
     */
    public static <T> Result<T> fail(int code, String message, String traceId) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = traceId;
        return r;
    }
}
