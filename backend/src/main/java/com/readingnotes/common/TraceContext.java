package com.readingnotes.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 trace_id（需求 §6.4.2）
 * 上传/构建/问答等关键操作各持一个 trace_id，贯穿日志与响应头 X-Trace-Id。
 */
@Slf4j
public final class TraceContext {

    private static final String MDC_KEY = "traceId";

    private TraceContext() {}

    /** 新开一个 trace_id（32 位无连字符） */
    public static String newTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    /** 取当前 trace_id，无则生成 */
    public static String current() {
        String t = MDC.get(MDC_KEY);
        if (t == null) {
            t = newTrace();
        }
        return t;
    }

    /** 显式沿用外部传入的 X-Trace-Id */
    public static void use(String externalTraceId) {
        if (externalTraceId != null && !externalTraceId.isBlank()) {
            MDC.put(MDC_KEY, externalTraceId.trim());
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
