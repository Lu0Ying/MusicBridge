package com.musicbridge.provider.circuit;

import com.musicbridge.config.ProviderProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：每个 Provider 一个实例，策略取自全局配置。
 */
@Component
public class CircuitBreakerRegistry {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ProviderProperties properties;
    private final Clock clock;

    public CircuitBreakerRegistry(ProviderProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public CircuitBreaker breaker(String providerId) {
        return breakers.computeIfAbsent(providerId, id -> {
            ProviderProperties.ProviderPolicy policy = properties.getPolicy();
            return new CircuitBreaker(policy.getCircuitFailureThreshold(),
                    Duration.ofMillis(policy.getCircuitOpenMs()),
                    policy.getHalfOpenPermits(), clock);
        });
    }

    public CircuitState state(String providerId) {
        return breaker(providerId).state();
    }

    public void reset(String providerId) {
        breakers.remove(providerId);
    }
}
