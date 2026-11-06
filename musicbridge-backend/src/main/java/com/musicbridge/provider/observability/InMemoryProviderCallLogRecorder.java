package com.musicbridge.provider.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存调用日志：仅用于 Phase 0 与测试观测，重启即丢失。
 * <p>正式环境应替换为写入 {@code provider_call_log} 的 JDBC 实现。</p>
 */
@Component
public class InMemoryProviderCallLogRecorder implements ProviderCallLogRecorder {

    private static final int MAX_ENTRIES = 2000;

    private final ConcurrentLinkedDeque<ProviderCallLog> logs = new ConcurrentLinkedDeque<>();

    @Override
    public void record(ProviderCallLog log) {
        if (log == null) {
            return;
        }
        logs.addFirst(log);
        while (logs.size() > MAX_ENTRIES) {
            logs.pollLast();
        }
    }

    @Override
    public List<ProviderCallLog> recent(int limit) {
        List<ProviderCallLog> result = new ArrayList<>();
        for (ProviderCallLog log : logs) {
            if (result.size() >= limit) {
                break;
            }
            result.add(log);
        }
        return result;
    }

    @Override
    public List<ProviderCallLog> byTraceId(String traceId, int limit) {
        List<ProviderCallLog> result = new ArrayList<>();
        if (traceId == null || traceId.isBlank()) {
            return result;
        }
        for (ProviderCallLog log : logs) {
            if (traceId.equals(log.traceId())) {
                result.add(log);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }
}
