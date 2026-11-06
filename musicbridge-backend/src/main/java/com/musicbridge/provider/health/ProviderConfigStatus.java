package com.musicbridge.provider.health;

import java.util.List;

/**
 * 静态配置检查结果：只读配置，不发起外部请求，不产生计费。
 */
public record ProviderConfigStatus(boolean ready, List<String> missingItems, String detail) {

    public ProviderConfigStatus {
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
    }

    public static ProviderConfigStatus ok() {
        return new ProviderConfigStatus(true, List.of(), "配置完整");
    }

    public static ProviderConfigStatus missing(String... items) {
        return new ProviderConfigStatus(false, List.of(items), "缺少必要配置");
    }
}
