package com.musicbridge.provider.cache;

import com.musicbridge.config.CacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-04：缓存 TTL 外置配置的绑定与合法性校验。
 */
class CacheTtlPolicyTest {

    @Test
    void bindsConfiguredTtlAndFallsBackToDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("musicbridge.cache.max-retention", "300d")
                .withProperty("musicbridge.cache.ttl.metadata", "1h")
                .withProperty("musicbridge.cache.ttl.negative", "15m")
                .withProperty("musicbridge.cache.ttl.lyrics-plain", "45d");

        CacheProperties properties = Binder.get(environment)
                .bind("musicbridge.cache", CacheProperties.class)
                .get();
        CacheTtlPolicy policy = new CacheTtlPolicy(properties);

        assertEquals(Duration.ofHours(1), policy.ttlFor(CacheCategory.METADATA));
        assertEquals(Duration.ofMinutes(15), policy.ttlFor(CacheCategory.NEGATIVE));
        assertEquals(Duration.ofDays(45), policy.ttlFor(CacheCategory.LYRICS_PLAIN));
        // 未显式配置的分类回落到枚举默认值
        assertEquals(CacheCategory.TRANSLITERATION.ttl(), policy.ttlFor(CacheCategory.TRANSLITERATION));
        assertEquals(Duration.ofDays(300), policy.maxRetention());
    }

    @Test
    void usesEnumDefaultsWhenNothingIsConfigured() {
        CacheTtlPolicy policy = new CacheTtlPolicy(new CacheProperties());
        for (CacheCategory category : CacheCategory.values()) {
            assertEquals(category.ttl(), policy.ttlFor(category), "默认值不一致: " + category);
        }
    }

    @Test
    void rejectsNonPositiveTtl() {
        CacheProperties properties = new CacheProperties();
        properties.getTtl().put(CacheCategory.METADATA, Duration.ZERO);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CacheTtlPolicy(properties));
        assertTrue(error.getMessage().contains("必须为正数"));
    }

    @Test
    void rejectsTtlLongerThanMaxRetention() {
        CacheProperties properties = new CacheProperties();
        properties.setMaxRetention(Duration.ofDays(10));
        properties.getTtl().put(CacheCategory.TRANSLITERATION, Duration.ofDays(11));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CacheTtlPolicy(properties));
        assertTrue(error.getMessage().contains("max-retention"));
    }

    @Test
    void rejectsNegativeCacheTtlOutside10To30Minutes() {
        CacheProperties tooShort = new CacheProperties();
        tooShort.getTtl().put(CacheCategory.NEGATIVE, Duration.ofMinutes(5));
        assertThrows(IllegalArgumentException.class, () -> new CacheTtlPolicy(tooShort));

        CacheProperties tooLong = new CacheProperties();
        tooLong.getTtl().put(CacheCategory.NEGATIVE, Duration.ofMinutes(31));
        assertThrows(IllegalArgumentException.class, () -> new CacheTtlPolicy(tooLong));
    }

    @Test
    void rejectsInvalidMaxRetention() {
        CacheProperties properties = new CacheProperties();
        properties.setMaxRetention(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> new CacheTtlPolicy(properties));
    }
}
