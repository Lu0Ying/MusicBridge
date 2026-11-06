package com.musicbridge.provider.cache;

import java.time.Duration;

/**
 * 缓存分类与 TTL 矩阵（报告 8.6）。
 */
public enum CacheCategory {

    METADATA(Duration.ofDays(7), false),
    COVER(Duration.ofDays(30), false),
    LYRICS_PLAIN(Duration.ofDays(30), false),
    LYRICS_SYNCED(Duration.ofDays(90), false),
    TRANSLATION(Duration.ofDays(90), false),
    TRANSLITERATION(Duration.ofDays(180), false),
    RECOGNITION(Duration.ofHours(24), false),
    SEARCH(Duration.ofHours(1), false),
    /** NO_MATCH 负缓存 10–30 分钟，只表示该 Provider 此刻没找到 */
    NEGATIVE(Duration.ofMinutes(20), true);

    private final Duration ttl;
    private final boolean negative;

    CacheCategory(Duration ttl, boolean negative) {
        this.ttl = ttl;
        this.negative = negative;
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean negative() {
        return negative;
    }
}
