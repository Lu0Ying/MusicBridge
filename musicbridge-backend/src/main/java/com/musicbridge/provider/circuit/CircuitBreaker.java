package com.musicbridge.provider.circuit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Provider 级熔断器。
 * <p>连续失败达到阈值后 OPEN，冷却期结束转 HALF_OPEN，仅放行少量请求；
 * 成功即 CLOSED，失败重新 OPEN。</p>
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final int halfOpenPermits;
    private final Clock clock;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private int halfOpenInFlight;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration, int halfOpenPermits, Clock clock) {
        this.failureThreshold = Math.max(failureThreshold, 1);
        this.openDuration = openDuration;
        this.halfOpenPermits = Math.max(halfOpenPermits, 1);
        this.clock = clock;
    }

    /** 是否允许本次调用；OPEN 且未到冷却期直接拒绝 */
    public synchronized boolean tryAcquire() {
        Instant now = clock.instant();
        if (state == CircuitState.OPEN) {
            if (now.isBefore(openedAt.plus(openDuration))) {
                return false;
            }
            state = CircuitState.HALF_OPEN;
            halfOpenInFlight = 0;
        }
        if (state == CircuitState.HALF_OPEN) {
            if (halfOpenInFlight >= halfOpenPermits) {
                return false;
            }
            halfOpenInFlight++;
        }
        return true;
    }

    public synchronized void onSuccess() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        halfOpenInFlight = 0;
        openedAt = null;
    }

    public synchronized void onFailure() {
        if (state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = CircuitState.OPEN;
        openedAt = clock.instant();
    }

    public synchronized CircuitState state() {
        if (state == CircuitState.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return CircuitState.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized void reset() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        halfOpenInFlight = 0;
        openedAt = null;
    }
}
