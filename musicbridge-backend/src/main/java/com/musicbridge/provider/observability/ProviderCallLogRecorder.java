package com.musicbridge.provider.observability;

import java.util.List;

/**
 * 调用日志记录器接口。Phase 0 先提供内存最小实现，
 * 后续可由 JDBC 实现写入 {@code provider_call_log} 表。
 */
public interface ProviderCallLogRecorder {

    void record(ProviderCallLog log);

    List<ProviderCallLog> recent(int limit);

    /** 按 traceId 查询，供链路排查使用 */
    List<ProviderCallLog> byTraceId(String traceId, int limit);
}
