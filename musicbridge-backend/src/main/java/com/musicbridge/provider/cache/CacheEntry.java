package com.musicbridge.provider.cache;

import com.musicbridge.provider.dto.SourceRef;

import java.time.Instant;
import java.util.List;

/**
 * 查询缓存条目：保留来源四件套、TTL、stale 标记与冲突值。
 * <p>origin=USER 或 userConfirmed=true 的字段，自动刷新不得覆盖。</p>
 */
public record CacheEntry(String cacheKey,
                         CacheCategory category,
                         Object value,
                         SourceRef source,
                         Instant retrievedAt,
                         Instant expiresAt,
                         Instant staleUntil,
                         CacheOrigin origin,
                         boolean userConfirmed,
                         List<String> conflicts,
                         String schemaVersion) {

    public CacheEntry {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public boolean usableAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    public boolean staleUsableAt(Instant now) {
        return staleUntil.isAfter(now);
    }
}
