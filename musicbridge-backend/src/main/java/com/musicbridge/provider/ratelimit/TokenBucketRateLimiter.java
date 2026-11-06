package com.musicbridge.provider.ratelimit;

/**
 * 进程内令牌桶限流器。
 * <p>所有 adapter 复用同一限流模块，禁止各自 sleep。</p>
 */
public class TokenBucketRateLimiter implements ProviderRateLimiter {

    private final double permitsPerSecond;
    private final int burstCapacity;

    private double storedTokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(double permitsPerSecond, int burstCapacity) {
        this.permitsPerSecond = Math.max(permitsPerSecond, 0.0001);
        this.burstCapacity = Math.max(burstCapacity, 1);
        this.storedTokens = this.burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    @Override
    public synchronized boolean tryAcquire() {
        refill();
        if (storedTokens < 1.0) {
            return false;
        }
        storedTokens -= 1.0;
        return true;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            return;
        }
        storedTokens = Math.min(burstCapacity, storedTokens + elapsedSeconds * permitsPerSecond);
        lastRefillNanos = now;
    }

    @Override
    public synchronized void reset() {
        storedTokens = burstCapacity;
        lastRefillNanos = System.nanoTime();
    }
}
