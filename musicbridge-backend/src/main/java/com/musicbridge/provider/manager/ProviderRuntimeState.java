package com.musicbridge.provider.manager;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 运行时状态：进程内停用、配额/限流冷却、能力级冷却。
 * <p>配置错误、401 与账号级 403 停用整个 Provider；功能级 403 只冷却该能力。</p>
 */
@Component
public class ProviderRuntimeState {

    /** providerId -> 停用原因 */
    private final Map<String, String> disabledProviders = new ConcurrentHashMap<>();

    /** providerId -> 整体冷却截止时间 */
    private final Map<String, Instant> providerCooldownUntil = new ConcurrentHashMap<>();

    /** providerId + capability -> 能力冷却截止时间 */
    private final Map<String, Instant> capabilityCooldownUntil = new ConcurrentHashMap<>();

    public void disableProvider(String providerId, String reason) {
        disabledProviders.put(providerId, reason == null ? "unknown" : reason);
    }

    public boolean isDisabled(String providerId) {
        return disabledProviders.containsKey(providerId);
    }

    public String disableReason(String providerId) {
        return disabledProviders.get(providerId);
    }

    public void enableProvider(String providerId) {
        disabledProviders.remove(providerId);
        providerCooldownUntil.remove(providerId);
    }

    public void cooldownProvider(String providerId, Instant until) {
        providerCooldownUntil.put(providerId, until);
    }

    public boolean isProviderCoolingDown(String providerId, Instant now) {
        Instant until = providerCooldownUntil.get(providerId);
        return until != null && now.isBefore(until);
    }

    public Instant providerCooldownUntil(String providerId) {
        return providerCooldownUntil.get(providerId);
    }

    public void cooldownCapability(String providerId, com.musicbridge.provider.ProviderCapability capability, Instant until) {
        capabilityCooldownUntil.put(capabilityKey(providerId, capability), until);
    }

    public boolean isCapabilityCoolingDown(String providerId, com.musicbridge.provider.ProviderCapability capability, Instant now) {
        Instant until = capabilityCooldownUntil.get(capabilityKey(providerId, capability));
        return until != null && now.isBefore(until);
    }

    public Instant capabilityCooldownUntil(String providerId, com.musicbridge.provider.ProviderCapability capability) {
        return capabilityCooldownUntil.get(capabilityKey(providerId, capability));
    }

    public void reset() {
        disabledProviders.clear();
        providerCooldownUntil.clear();
        capabilityCooldownUntil.clear();
    }

    private String capabilityKey(String providerId, com.musicbridge.provider.ProviderCapability capability) {
        return providerId + ":" + capability.name();
    }
}
