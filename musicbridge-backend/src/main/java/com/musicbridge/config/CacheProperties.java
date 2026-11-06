package com.musicbridge.config;

import com.musicbridge.provider.cache.CacheCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存配置：TTL 矩阵从代码外置到配置文件，未配置的分类回落到 {@link CacheCategory} 默认值。
 */
@ConfigurationProperties(prefix = "musicbridge.cache")
public class CacheProperties {

    /** 缓存条目在 Caffeine 中的最长保留时间，兜底回收 */
    private Duration maxRetention = Duration.ofDays(200);

    /** 分类 -> TTL；键为分类名（大小写与连字符不敏感） */
    private Map<CacheCategory, Duration> ttl = new LinkedHashMap<>();

    public Duration getMaxRetention() {
        return maxRetention;
    }

    public void setMaxRetention(Duration maxRetention) {
        this.maxRetention = maxRetention;
    }

    public Map<CacheCategory, Duration> getTtl() {
        return ttl;
    }

    public void setTtl(Map<CacheCategory, Duration> ttl) {
        this.ttl = ttl == null ? new LinkedHashMap<>() : ttl;
    }
}
