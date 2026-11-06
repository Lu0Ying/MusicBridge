package com.musicbridge.provider.ratelimit;

/**
 * Provider 级限流器抽象。
 * <p>Phase 0 使用进程内令牌桶；多实例由 {@link SharedRateLimiterStore} 预留共享存储扩展点。</p>
 */
public interface ProviderRateLimiter {

    /** 尝试获取一个调用令牌，不阻塞 */
    boolean tryAcquire();

    /** 重置令牌（测试或配置变更后使用） */
    void reset();
}
