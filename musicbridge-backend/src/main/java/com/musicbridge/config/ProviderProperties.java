package com.musicbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 配置：启用开关、优先级、超时、限流、成本与密钥占位。
 * <p>本机与远程使用同一套配置项，密钥只从环境变量读取，禁止硬编码。</p>
 */
@ConfigurationProperties(prefix = "musicbridge.providers")
public class ProviderProperties {

    /** 主动探测总开关，默认关闭，避免健康检查产生第三方计费 */
    private boolean probeEnabled = false;

    /** 允许主动探测的 Provider id 白名单 */
    private List<String> probeWhitelist = new ArrayList<>();

    private ProviderPolicy policy = new ProviderPolicy();

    private Map<String, ProviderSetting> entries = new LinkedHashMap<>();

    public ProviderSetting settingFor(String providerId) {
        ProviderSetting setting = entries.get(providerId);
        return setting == null ? new ProviderSetting() : setting;
    }

    public boolean isProbeAllowed(String providerId) {
        return probeEnabled && probeWhitelist.contains(providerId);
    }

    public boolean isProbeEnabled() {
        return probeEnabled;
    }

    public void setProbeEnabled(boolean probeEnabled) {
        this.probeEnabled = probeEnabled;
    }

    public List<String> getProbeWhitelist() {
        return probeWhitelist;
    }

    public void setProbeWhitelist(List<String> probeWhitelist) {
        this.probeWhitelist = probeWhitelist == null ? new ArrayList<>() : probeWhitelist;
    }

    public ProviderPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(ProviderPolicy policy) {
        this.policy = policy;
    }

    public Map<String, ProviderSetting> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, ProviderSetting> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : entries;
    }

    /**
     * 单个 Provider 的配置项。缺省 enabled=false：未显式启用的 Provider 不参与路由。
     */
    public static class ProviderSetting {

        private boolean enabled = false;
        private int priority = 100;
        private long timeoutMs = 15000;
        private int maxRetries = 3;
        private double requestsPerSecond = 1;
        private int burstCapacity = 1;
        private BigDecimal costPerUnit = BigDecimal.ZERO;
        private String apiKey = "";
        private String baseUrl = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public BigDecimal getCostPerUnit() {
            return costPerUnit;
        }

        public void setCostPerUnit(BigDecimal costPerUnit) {
            this.costPerUnit = costPerUnit;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * 全局策略：单任务预算、重试、熔断与冷却，对应报告 8.5。
     */
    public static class ProviderPolicy {

        private int maxExternalCallsPerTask = 5;
        private int maxSwitchesPerTask = 2;
        private long retryBaseMs = 200;
        private int maxRetries = 3;
        private int circuitFailureThreshold = 3;
        private long circuitOpenMs = 60000;
        private int halfOpenPermits = 1;
        private long retryAfterSwitchThresholdMs = 10000;
        private long defaultRetryAfterMs = 60000;
        private long capabilityCooldownMs = 86400000;
        private long quotaCooldownMs = 3600000;

        public int getMaxExternalCallsPerTask() {
            return maxExternalCallsPerTask;
        }

        public void setMaxExternalCallsPerTask(int maxExternalCallsPerTask) {
            this.maxExternalCallsPerTask = maxExternalCallsPerTask;
        }

        public int getMaxSwitchesPerTask() {
            return maxSwitchesPerTask;
        }

        public void setMaxSwitchesPerTask(int maxSwitchesPerTask) {
            this.maxSwitchesPerTask = maxSwitchesPerTask;
        }

        public long getRetryBaseMs() {
            return retryBaseMs;
        }

        public void setRetryBaseMs(long retryBaseMs) {
            this.retryBaseMs = retryBaseMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getCircuitFailureThreshold() {
            return circuitFailureThreshold;
        }

        public void setCircuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
        }

        public long getCircuitOpenMs() {
            return circuitOpenMs;
        }

        public void setCircuitOpenMs(long circuitOpenMs) {
            this.circuitOpenMs = circuitOpenMs;
        }

        public int getHalfOpenPermits() {
            return halfOpenPermits;
        }

        public void setHalfOpenPermits(int halfOpenPermits) {
            this.halfOpenPermits = halfOpenPermits;
        }

        public long getRetryAfterSwitchThresholdMs() {
            return retryAfterSwitchThresholdMs;
        }

        public void setRetryAfterSwitchThresholdMs(long retryAfterSwitchThresholdMs) {
            this.retryAfterSwitchThresholdMs = retryAfterSwitchThresholdMs;
        }

        public long getDefaultRetryAfterMs() {
            return defaultRetryAfterMs;
        }

        public void setDefaultRetryAfterMs(long defaultRetryAfterMs) {
            this.defaultRetryAfterMs = defaultRetryAfterMs;
        }

        public long getCapabilityCooldownMs() {
            return capabilityCooldownMs;
        }

        public void setCapabilityCooldownMs(long capabilityCooldownMs) {
            this.capabilityCooldownMs = capabilityCooldownMs;
        }

        public long getQuotaCooldownMs() {
            return quotaCooldownMs;
        }

        public void setQuotaCooldownMs(long quotaCooldownMs) {
            this.quotaCooldownMs = quotaCooldownMs;
        }
    }
}
