package com.musicbridge.provider.health;

import java.time.Instant;

/**
 * 主动探测结果。默认关闭，仅对白名单 Provider 触发并缓存 5–15 分钟。
 */
public record ProviderHealth(ProviderHealthStatus status, String message, Instant checkedAt, boolean activeProbe) {

    public static ProviderHealth unknown(String message) {
        return new ProviderHealth(ProviderHealthStatus.UNKNOWN, message, Instant.now(), false);
    }

    public static ProviderHealth ok(String message) {
        return new ProviderHealth(ProviderHealthStatus.OK, message, Instant.now(), true);
    }
}
