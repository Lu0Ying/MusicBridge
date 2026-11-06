package com.musicbridge.provider;

import com.musicbridge.common.RequestContext;
import com.musicbridge.config.ProviderProperties;
import com.musicbridge.provider.cache.CacheCategory;
import com.musicbridge.provider.cache.CaffeineProviderQueryCache;
import com.musicbridge.provider.cache.SingleFlight;
import com.musicbridge.provider.circuit.CircuitBreakerRegistry;
import com.musicbridge.provider.dto.MetadataRecord;
import com.musicbridge.provider.dto.RecordingDTO;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.dto.VersionType;
import com.musicbridge.provider.dto.WorkDTO;
import com.musicbridge.provider.error.ProviderErrorCode;
import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.manager.ProviderCallExecutor;
import com.musicbridge.provider.manager.ProviderManager;
import com.musicbridge.provider.manager.ProviderRuntimeState;
import com.musicbridge.provider.manager.ProviderTask;
import com.musicbridge.provider.manager.Sleeper;
import com.musicbridge.provider.metadata.MetadataProvider;
import com.musicbridge.provider.metadata.MetadataQuery;
import com.musicbridge.provider.observability.DefaultCostEstimator;
import com.musicbridge.provider.observability.InMemoryProviderCallLogRecorder;
import com.musicbridge.provider.observability.ProviderUsageStats;
import com.musicbridge.provider.ratelimit.RateLimiterRegistry;
import com.musicbridge.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础可靠性测试：缓存 single-flight 与并发限流。
 * 全部使用进程内假 Provider，不访问外部网络。
 */
class ConcurrencyReliabilityTest {

    private static final int THREADS = 8;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ProviderCallExecutor callExecutor = new ProviderCallExecutor();
    private final ProviderRuntimeState runtimeState = new ProviderRuntimeState();
    private final InMemoryProviderCallLogRecorder recorder = new InMemoryProviderCallLogRecorder();
    private final SingleFlight singleFlight = new SingleFlight();
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void tearDown() {
        callExecutor.shutdown();
        pool.shutdownNow();
    }

    @Test
    void singleFlightCollapsesConcurrentIdenticalRequests() throws Exception {
        ProviderProperties properties = new ProviderProperties();
        enable(properties, "slow-metadata", 1000, 1000, 0);
        CountingMetadataProvider provider = new CountingMetadataProvider("slow-metadata", 120);

        ProviderManager manager = buildManager(properties, List.of(provider));
        MetadataQuery query = MetadataQuery.byRecordingMbid("rec-shared");
        CyclicBarrier barrier = new CyclicBarrier(THREADS);

        List<Future<MetadataRecord>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return manager.lookupMetadata(query, RequestContext.create("user-1"));
            }));
        }

        for (Future<MetadataRecord> future : futures) {
            MetadataRecord record = future.get(10, TimeUnit.SECONDS);
            assertNotNull(record);
        }
        assertEquals(1, provider.calls.get(),
                "同一缓存键的并发请求必须只触发一次 Provider 调用");
        assertEquals(0, singleFlight.inFlightCount(), "在途标记必须在执行结束后清理");
    }

    @Test
    void rateLimiterBlocksConcurrentCallsBeyondBurstCapacity() throws Exception {
        ProviderProperties properties = new ProviderProperties();
        // 极低补充速率 + 桶容量 1：并发中只允许 1 次通过
        enable(properties, "limited-metadata", 0.0001, 1, 0);
        CountingMetadataProvider provider = new CountingMetadataProvider("limited-metadata", 0);

        ProviderManager manager = buildManager(properties, List.of(provider));
        MetadataQuery query = MetadataQuery.byRecordingMbid("rec-limited");
        ProviderTask<MetadataRecord> task = new ProviderTask<>("metadata",
                ProviderCapability.METADATA, null,
                candidate -> candidate instanceof MetadataProvider,
                (candidate, ctx) -> ((MetadataProvider) candidate).lookup(query, ctx),
                MetadataRecord::hasMatch,
                () -> new MetadataRecord(null, null, List.of(), List.of(), List.of(), null, null),
                null, CacheCategory.METADATA, 1);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return manager.execute(task, RequestContext.create("user-1"));
                } catch (ProviderException e) {
                    return e;
                }
            }));
        }

        int successes = 0;
        int rateLimited = 0;
        int failures = 0;
        for (Future<Object> future : futures) {
            Object outcome = future.get(10, TimeUnit.SECONDS);
            if (outcome instanceof ProviderException error) {
                // 首个被拒请求得到 RATE_LIMITED；随后 Provider 进入冷却，其余请求在候选筛选阶段被拒
                assertTrue(error.errorCode() == ProviderErrorCode.RATE_LIMITED
                                || error.errorCode() == ProviderErrorCode.NO_MATCH,
                        "并发超额请求必须被限流或直接无可用 Provider，实际: " + error.errorCode());
                if (error.errorCode() == ProviderErrorCode.RATE_LIMITED) {
                    rateLimited++;
                }
                failures++;
            } else {
                successes++;
            }
        }

        assertEquals(1, provider.calls.get(), "只有拿到令牌的请求可以真正调用 Provider");
        assertEquals(1, successes);
        assertEquals(THREADS - 1, failures);
        assertTrue(rateLimited >= 1, "至少一个请求必须收到明确的 RATE_LIMITED 结果");
        assertTrue(recorder.recent(THREADS).stream()
                .anyMatch(log -> "limited-metadata".equals(log.provider())),
                "限流结果必须写入调用日志");
    }

    // ---------------------------------------------------------------- 辅助

    private void enable(ProviderProperties properties, String providerId, double rps, int burst, int maxRetries) {
        ProviderProperties.ProviderSetting setting = new ProviderProperties.ProviderSetting();
        setting.setEnabled(true);
        setting.setPriority(10);
        setting.setMaxRetries(maxRetries);
        setting.setRequestsPerSecond(rps);
        setting.setBurstCapacity(burst);
        setting.setTimeoutMs(5000);
        properties.getEntries().put(providerId, setting);
    }

    private ProviderManager buildManager(ProviderProperties properties, List<MusicProvider> providers) {
        ProviderRegistry registry = new ProviderRegistry(providers, properties, new MockEnvironment());
        return new ProviderManager(registry, properties, new CaffeineProviderQueryCache(),
                singleFlight, new RateLimiterRegistry(properties), callExecutor,
                new CircuitBreakerRegistry(properties, clock), runtimeState, recorder,
                new ProviderUsageStats(), new DefaultCostEstimator(properties), Sleeper.noop(), clock);
    }

    /**
     * 统计调用次数并模拟耗时的元数据 Provider。
     */
    private static final class CountingMetadataProvider implements MetadataProvider {

        private final String id;
        private final long delayMs;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingMetadataProvider(String id, long delayMs) {
            this.id = id;
            this.delayMs = delayMs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ProviderCapability capability() {
            return ProviderCapability.METADATA;
        }

        @Override
        public MetadataRecord lookup(MetadataQuery query, RequestContext ctx) {
            calls.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new MetadataRecord(
                    new WorkDTO("work-1", "晴天", List.of("周杰伦"), List.of("周杰伦")),
                    new RecordingDTO("rec-1", "晴天", List.of("周杰伦"), Map.of(),
                            Duration.ofSeconds(269), null, VersionType.ORIGINAL, "work-1"),
                    List.of(), List.of(), List.of(), 0.9,
                    new SourceRef(id, query.recordingMbid(), "MOCK", Instant.now()));
        }

        @Override
        public ProviderConfigStatus checkConfig() {
            return ProviderConfigStatus.ok();
        }

        @Override
        public ProviderHealth probeHealth() {
            return ProviderHealth.ok("fake");
        }
    }
}
