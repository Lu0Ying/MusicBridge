package com.musicbridge.provider.admin;

import com.musicbridge.config.ProviderProperties;
import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.circuit.CircuitBreakerRegistry;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.manager.ProviderRuntimeState;
import com.musicbridge.provider.registry.ProviderRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 管理查询服务：静态状态列表与白名单主动探测。
 * <p>主动探测默认关闭，结果缓存 10 分钟，避免健康检查产生第三方计费。</p>
 */
@Service
public class ProviderAdminService {

    private static final Duration PROBE_CACHE_TTL = Duration.ofMinutes(10);

    private final ProviderRegistry registry;
    private final ProviderProperties properties;
    private final ProviderRuntimeState runtimeState;
    private final CircuitBreakerRegistry circuitRegistry;
    private final Clock clock;
    private final Map<String, CachedHealth> probeCache = new ConcurrentHashMap<>();

    public ProviderAdminService(ProviderRegistry registry,
                                ProviderProperties properties,
                                ProviderRuntimeState runtimeState,
                                CircuitBreakerRegistry circuitRegistry,
                                Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.runtimeState = runtimeState;
        this.circuitRegistry = circuitRegistry;
        this.clock = clock;
    }

    public List<ProviderStatusDTO> listProviders() {
        List<ProviderStatusDTO> result = new ArrayList<>();
        for (MusicProvider provider : registry.all()) {
            ProviderConfigStatus config = provider.checkConfig();
            ProviderProperties.ProviderSetting setting = properties.settingFor(provider.id());
            result.add(new ProviderStatusDTO(provider.id(), provider.capability(),
                    setting.isEnabled(), setting.getPriority(),
                    config.ready(), config.missingItems(),
                    circuitRegistry.state(provider.id()),
                    runtimeState.isDisabled(provider.id()),
                    runtimeState.disableReason(provider.id()),
                    runtimeState.providerCooldownUntil(provider.id()),
                    runtimeState.capabilityCooldownUntil(provider.id(), provider.capability())));
        }
        return result;
    }

    public ProviderHealth probe(String providerId) {
        MusicProvider provider = registry.find(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Provider 不存在: " + providerId));
        if (!properties.isProbeAllowed(providerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "主动探测默认关闭，且该 Provider 不在白名单");
        }
        Instant now = clock.instant();
        CachedHealth cached = probeCache.get(providerId);
        if (cached != null && now.isBefore(cached.cachedAt().plus(PROBE_CACHE_TTL))) {
            return cached.health();
        }
        ProviderHealth health = provider.probeHealth();
        probeCache.put(providerId, new CachedHealth(health, now));
        return health;
    }

    private record CachedHealth(ProviderHealth health, Instant cachedAt) {
    }
}
