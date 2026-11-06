package com.musicbridge.provider.registry;

import com.musicbridge.config.ProviderProperties;
import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.ProviderCapability;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider 注册表：负责注册、按能力检索与启用状态校验。
 * <p>选择、降级、熔断由 ProviderManager 负责，本类不发起调用。</p>
 */
@Component
public class ProviderRegistry {

    private final Map<String, MusicProvider> providers = new LinkedHashMap<>();
    private final ProviderProperties properties;

    public ProviderRegistry(List<MusicProvider> providers, ProviderProperties properties, Environment environment) {
        this.properties = properties;
        providers.forEach(this::add);
        guardAgainstMockInProduction(environment);
    }

    private void guardAgainstMockInProduction(Environment environment) {
        boolean production = List.of(environment.getActiveProfiles()).contains("prod");
        if (!production) {
            return;
        }
        List<String> enabledMocks = properties.getEntries().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("mock-") && entry.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .toList();
        if (!enabledMocks.isEmpty()) {
            throw new IllegalStateException("生产配置不允许启用 Mock Provider: " + enabledMocks);
        }
    }

    private void add(MusicProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("Provider 必须有非空 id");
        }
        MusicProvider previous = providers.putIfAbsent(provider.id(), provider);
        if (previous != null) {
            throw new IllegalStateException("Provider id 重复: " + provider.id());
        }
    }

    /** 测试可注册临时 Provider；生产由 Spring 注入列表完成注册 */
    public synchronized void register(MusicProvider provider) {
        add(provider);
    }

    public Optional<MusicProvider> find(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    public List<MusicProvider> all() {
        return new ArrayList<>(providers.values());
    }

    /**
     * 按能力过滤并按优先级升序返回（数字越小优先级越高）。
     */
    public List<MusicProvider> candidates(ProviderCapability capability) {
        return providers.values().stream()
                .filter(provider -> provider.capability() == capability)
                .sorted(Comparator.comparingInt(provider -> properties.settingFor(provider.id()).getPriority()))
                .toList();
    }

    public List<MusicProvider> enabledCandidates(ProviderCapability capability) {
        return candidates(capability).stream()
                .filter(provider -> properties.settingFor(provider.id()).isEnabled())
                .toList();
    }
}
