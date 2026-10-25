package com.readingnotes.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 trace_id（需求 §6.4.2）。
 * <p>
 * 上传/构建/问答等关键操作各持一个 trace_id，贯穿日志与响应头 X-Trace-Id。
 * 依赖 SLF4J MDC，因此只在当前线程有效；异步任务需在新线程入口显式 {@link #use(String)} 传递，
 * 否则日志里的 trace 会断链（构建 worker 属于这种情况）。
 */
@Slf4j
public final class TraceContext {

    private static final String MDC_KEY = "traceId";

    private TraceContext() {}

    /** 新开一个 trace_id（32 位无连字符），并写入 MDC */
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

    /** 显式沿用外部传入的 X-Trace-Id（空值忽略，不覆盖已有 trace） */
    public static void use(String externalTraceId) {
        if (externalTraceId != null && !externalTraceId.isBlank()) {
            MDC.put(MDC_KEY, externalTraceId.trim());
        }
    }

    /** 清理当前线程的 trace_id，必须在请求结束时调用，避免线程池复用导致串号 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
