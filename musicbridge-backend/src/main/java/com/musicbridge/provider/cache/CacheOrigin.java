package com.musicbridge.provider.cache;

/**
 * 缓存条目来源：自动刷新不得覆盖 USER 数据。
 */
public enum CacheOrigin {
    PROVIDER,
    LOCAL,
    USER
}
