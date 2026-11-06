package com.musicbridge.provider.cache;

import java.util.Optional;

/**
 * 查询缓存接口。Phase 0 使用 Caffeine 进程内实现；
 * 可替换为共享缓存实现而不改动业务层。
 */
public interface ProviderQueryCache {

    Optional<CacheEntry> get(String cacheKey);

    void put(CacheEntry entry);

    void invalidate(String cacheKey);

    /** 按前缀清理，供管理端缓存清理使用 */
    void invalidateByPrefix(String prefix);

    long estimatedSize();
}
