package com.musicbridge.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 单次请求的 trace 上下文。
 * <p>由 {@code TraceFilter} 在入口处写入 MDC，请求内任意层（含 Provider 调用）读取，
 * 保证 HTTP 入口、调用日志与业务日志使用同一个 traceId。</p>
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";
    public static final String REQUEST_ATTRIBUTE = "musicbridge.traceId";

    private TraceContext() {
    }

    public static void start(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    /** 请求链路内复用当前 traceId；不在请求链路内时生成新值 */
    public static String currentOrNew() {
        String current = currentTraceId();
        return current != null && !current.isBlank() ? current : UUID.randomUUID().toString();
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
