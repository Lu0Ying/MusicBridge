package com.musicbridge.provider.ratelimit;

import com.musicbridge.config.ProviderProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Provider id 管理限流器实例，配置变更后可通过 {@link #reset(String)} 重建。
 */
@Component
public class RateLimiterRegistry {

    private final ProviderProperties properties;
    private final Map<String, ProviderRateLimiter> limiters = new ConcurrentHashMap<>();

    public RateLimiterRegistry(ProviderProperties properties) {
        this.properties = properties;
    }

    public ProviderRateLimiter limiter(String providerId) {
        return limiters.computeIfAbsent(providerId, id -> {
            ProviderProperties.ProviderSetting setting = properties.settingFor(id);
            return new TokenBucketRateLimiter(setting.getRequestsPerSecond(), setting.getBurstCapacity());
        });
    }

    public boolean tryAcquire(String providerId) {
        return limiter(providerId).tryAcquire();
    }

    public void reset(String providerId) {
        limiters.remove(providerId);
    }
}
