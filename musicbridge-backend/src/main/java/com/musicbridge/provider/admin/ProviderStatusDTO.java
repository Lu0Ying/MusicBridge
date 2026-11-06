package com.musicbridge.provider.admin;

import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.circuit.CircuitState;

import java.time.Instant;
import java.util.List;

/**
 * 管理端 Provider 状态视图（报告 8.9：熔断状态、限流余量、最近切换原因可见的简化版）。
 */
public record ProviderStatusDTO(String providerId,
                                ProviderCapability capability,
                                boolean enabled,
                                int priority,
                                boolean configReady,
                                List<String> missingConfigItems,
                                CircuitState circuitState,
                                boolean runtimeDisabled,
                                String runtimeDisableReason,
                                Instant providerCooldownUntil,
                                Instant capabilityCooldownUntil) {

    public ProviderStatusDTO {
        missingConfigItems = missingConfigItems == null ? List.of() : List.copyOf(missingConfigItems);
    }
}
