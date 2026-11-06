package com.musicbridge.provider.ratelimit;

/**
 * 多实例共享限流存储扩展点。
 * <p>Phase 0 不实现；后续可接 Redis Lua 原子扣减或 MySQL GET_LOCK，
 * 保证多实例部署时仍遵守 MusicBrainz 1 req/s、LRCLIB 等限流要求。</p>
 */
public interface SharedRateLimiterStore {

    boolean tryAcquire(String providerId, double permitsPerSecond, int burstCapacity);
}
