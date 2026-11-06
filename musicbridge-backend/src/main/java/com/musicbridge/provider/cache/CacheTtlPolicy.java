package com.musicbridge.provider.cache;

import com.musicbridge.config.CacheProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 缓存 TTL 解析与合法性校验。
 *
 * <p>启动时校验一次，避免非法配置在运行期才暴露：TTL 必须为正且不超过最长保留时间；
 * 负缓存 TTL 限制在 10–30 分钟（报告 8.6 的取值区间）。未配置的分类使用枚举默认值。</p>
 */
@Component
public class CacheTtlPolicy {

    private static final Duration MIN_NEGATIVE_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_NEGATIVE_TTL = Duration.ofMinutes(30);

    private final Map<CacheCategory, Duration> ttls;
    private final Duration maxRetention;

    public CacheTtlPolicy(CacheProperties properties) {
        this.maxRetention = properties.getMaxRetention();
        if (maxRetention == null || maxRetention.isNegative() || maxRetention.isZero()) {
            throw new IllegalArgumentException("musicbridge.cache.max-retention 必须为正数");
        }
        Map<CacheCategory, Duration> resolved = new EnumMap<>(CacheCategory.class);
        for (CacheCategory category : CacheCategory.values()) {
            Duration configured = properties.getTtl().get(category);
            Duration ttl = configured == null ? category.ttl() : configured;
            validate(category, ttl);
            resolved.put(category, ttl);
        }
        this.ttls = resolved;
    }

    public Duration ttlFor(CacheCategory category) {
        return ttls.get(category);
    }

    public Duration maxRetention() {
        return maxRetention;
    }

    private void validate(CacheCategory category, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException(
                    "缓存 TTL 必须为正数: " + category + "=" + ttl);
        }
        if (ttl.compareTo(maxRetention) > 0) {
            throw new IllegalArgumentException(
                    "缓存 TTL 不能超过 max-retention(" + maxRetention + "): " + category + "=" + ttl);
        }
        if (category.negative()
                && (ttl.compareTo(MIN_NEGATIVE_TTL) < 0 || ttl.compareTo(MAX_NEGATIVE_TTL) > 0)) {
            throw new IllegalArgumentException(
                    "负缓存 TTL 必须在 10–30 分钟之间: " + category + "=" + ttl);
        }
    }
}
