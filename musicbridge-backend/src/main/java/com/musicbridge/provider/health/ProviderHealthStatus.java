package com.musicbridge.provider.health;

/**
 * 主动探测的健康状态。
 */
public enum ProviderHealthStatus {
    OK,
    DEGRADED,
    UNAVAILABLE,
    /** 未执行主动探测（默认状态） */
    UNKNOWN
}
