package com.musicbridge.provider.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicbridge.config.CacheProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于 Caffeine 的进程内查询缓存最小实现。
 * <p>条目自身携带 expiresAt/staleUntil，缓存只负责存储，不做业务过期判定。</p>
 */
@Component
public class CaffeineProviderQueryCache implements ProviderQueryCache {

    private final Cache<String, CacheEntry> cache;

    public CaffeineProviderQueryCache(CacheProperties properties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.getMaxRetention())
                .build();
    }

    @Override
    public Optional<CacheEntry> get(String cacheKey) {
        return Optional.ofNullable(cache.getIfPresent(cacheKey));
    }

    @Override
    public void put(CacheEntry entry) {
        if (entry == null || entry.cacheKey() == null) {
            return;
        }
        cache.put(entry.cacheKey(), entry);
    }

    @Override
    public void invalidate(String cacheKey) {
        cache.invalidate(cacheKey);
    }

    @Override
    public void invalidateByPrefix(String prefix) {
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
