package com.readingnotes.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 统一响应信封（API 文档 §2.1）
 * { code, message, data, trace_id }
 */
@Data
public class Result<T> {

    /** 0 = 成功；非 0 见 ErrorCode */
    private int code;
    private String message;
    private T data;

    /** 序列化为文档约定的 trace_id（响应头仍为 X-Trace-Id） */
    @JsonProperty("trace_id")
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        r.traceId = TraceContext.current();
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = TraceContext.current();
        return r;
    }

    public static <T> Result<T> fail(int code, String message, String traceId) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = traceId;
        return r;
    }
}
