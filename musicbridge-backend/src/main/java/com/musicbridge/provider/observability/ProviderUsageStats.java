package com.musicbridge.provider.observability;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider 用量与成本统计最小实现（内存）。
 */
@Component
public class ProviderUsageStats {

    private final Map<String, Counters> counters = new ConcurrentHashMap<>();

    public void recordSuccess(String providerId, long latencyMs, BigDecimal cost) {
        Counters c = counters.computeIfAbsent(providerId, id -> new Counters());
        c.calls.incrementAndGet();
        c.successes.incrementAndGet();
        c.totalLatencyMs.addAndGet(Math.max(latencyMs, 0));
        c.maxLatencyMs.accumulateAndGet(latencyMs, Math::max);
        c.totalCost.accumulateAndGet(cost == null ? BigDecimal.ZERO : cost, BigDecimal::add);
    }

    public void recordFailure(String providerId, long latencyMs, BigDecimal cost) {
        Counters c = counters.computeIfAbsent(providerId, id -> new Counters());
        c.calls.incrementAndGet();
        c.failures.incrementAndGet();
        c.totalLatencyMs.addAndGet(Math.max(latencyMs, 0));
        c.maxLatencyMs.accumulateAndGet(latencyMs, Math::max);
        c.totalCost.accumulateAndGet(cost == null ? BigDecimal.ZERO : cost, BigDecimal::add);
    }

    public Map<String, Summary> snapshot() {
        Map<String, Summary> result = new LinkedHashMap<>();
        counters.forEach((providerId, c) -> result.put(providerId, c.toSummary()));
        return result;
    }

    public void reset() {
        counters.clear();
    }

    /** 单个 Provider 的统计快照 */
    public record Summary(long calls, long successes, long failures, double successRate,
                          double averageLatencyMs, long maxLatencyMs, BigDecimal totalCost) {
    }

    private static final class Counters {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong totalLatencyMs = new AtomicLong();
        private final AtomicLong maxLatencyMs = new AtomicLong();
        private final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);

        private Summary toSummary() {
            long callCount = calls.get();
            long successCount = successes.get();
            long latency = totalLatencyMs.get();
            double rate = callCount == 0 ? 0 : (double) successCount / callCount;
            double avg = callCount == 0 ? 0 : (double) latency / callCount;
            return new Summary(callCount, successCount, failures.get(), rate, avg,
                    maxLatencyMs.get(), totalCost.get());
        }
    }
}
