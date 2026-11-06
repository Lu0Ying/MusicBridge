package com.musicbridge.provider;

import com.musicbridge.common.RequestContext;
import com.musicbridge.config.ProviderProperties;
import com.musicbridge.config.CacheProperties;
import com.musicbridge.provider.cache.CacheTtlPolicy;
import com.musicbridge.provider.cache.CaffeineProviderQueryCache;
import com.musicbridge.provider.cache.SingleFlight;
import com.musicbridge.provider.circuit.CircuitBreakerRegistry;
import com.musicbridge.provider.circuit.CircuitState;
import com.musicbridge.provider.dto.MetadataRecord;
import com.musicbridge.provider.error.ProviderErrorCode;
import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.lyrics.LyricsDTO;
import com.musicbridge.provider.lyrics.LyricsQuery;
import com.musicbridge.provider.manager.ProviderBudgetExhaustedException;
import com.musicbridge.provider.manager.ProviderCallExecutor;
import com.musicbridge.provider.manager.ProviderManager;
import com.musicbridge.provider.manager.ProviderRuntimeState;
import com.musicbridge.provider.manager.Sleeper;
import com.musicbridge.provider.metadata.MetadataProvider;
import com.musicbridge.provider.metadata.MetadataQuery;
import com.musicbridge.provider.mock.MockLyricsProvider;
import com.musicbridge.provider.mock.MockMetadataProvider;
import com.musicbridge.provider.mock.MockRecognitionProvider;
import com.musicbridge.provider.mock.MockTranslationProvider;
import com.musicbridge.provider.mock.MockTransliterationProvider;
import com.musicbridge.provider.observability.DefaultCostEstimator;
import com.musicbridge.provider.observability.InMemoryProviderCallLogRecorder;
import com.musicbridge.provider.observability.ProviderCallLog;
import com.musicbridge.provider.observability.ProviderUsageStats;
import com.musicbridge.provider.ratelimit.RateLimiterRegistry;
import com.musicbridge.provider.recognition.DefaultRecognitionCapabilities;
import com.musicbridge.provider.recognition.RecognitionCandidate;
import com.musicbridge.provider.recognition.RecognitionCapabilities;
import com.musicbridge.provider.recognition.RecognitionInputType;
import com.musicbridge.provider.recognition.RecognitionProvider;
import com.musicbridge.provider.recognition.RecognitionRequest;
import com.musicbridge.provider.recognition.RecognitionResult;
import com.musicbridge.provider.registry.ProviderRegistry;
import com.musicbridge.provider.translation.TranslationRequest;
import com.musicbridge.provider.translation.TranslationResult;
import com.musicbridge.provider.transliteration.TransliterationRequest;
import com.musicbridge.provider.transliteration.TransliterationResult;
import com.musicbridge.provider.transliteration.TransliterationScheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProviderManager 路由、降级、预算、熔断与缓存测试。
 * 全部使用进程内假 Provider，不访问外部网络，不使用真实密钥。
 */
class ProviderManagerRoutingTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ProviderCallExecutor callExecutor = new ProviderCallExecutor();
    private final InMemoryProviderCallLogRecorder recorder = new InMemoryProviderCallLogRecorder();
    private final ProviderRuntimeState runtimeState = new ProviderRuntimeState();
    private final RequestContext ctx = RequestContext.create("test-user");
    private ProviderManager manager;
    private ProviderProperties properties;
    private CircuitBreakerRegistry circuitRegistry;

    @AfterEach
    void tearDown() {
        callExecutor.shutdown();
    }

    @Test
    void routesToHighestPriorityEnabledProvider() {
        properties = new ProviderProperties();
        enable("primary", 10, 3);
        enable("secondary", 100, 3);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("primary"));
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-1"), ctx);

        assertEquals("primary", result.providerId());
        assertEquals(1, primary.calls.get());
        assertEquals(0, secondary.calls.get());
    }

    @Test
    void skipsProviderThatDoesNotSupportInputType() {
        properties = new ProviderProperties();
        enable("raw-only", 10, 3);
        enable("fingerprint-only", 100, 3);
        ScriptedRecognitionProvider rawOnly = new ScriptedRecognitionProvider("raw-only",
                Set.of(RecognitionInputType.RAW_AUDIO), () -> success("raw-only"));
        ScriptedRecognitionProvider fingerprintOnly = new ScriptedRecognitionProvider("fingerprint-only",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("fingerprint-only"));
        manager = buildManager(List.of(rawOnly, fingerprintOnly));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-1"), ctx);

        assertEquals("fingerprint-only", result.providerId());
        assertEquals(0, rawOnly.calls.get());
    }

    @Test
    void fallsBackToNextProviderOnTemporaryFailureAndShowsSwitchInLog() {
        properties = new ProviderProperties();
        enable("primary", 10, 0);
        enable("secondary", 100, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-2"), ctx);

        assertEquals("secondary", result.providerId());
        List<ProviderCallLog> logs = recorder.recent(10);
        ProviderCallLog switchLog = logs.stream()
                .filter(log -> "secondary".equals(log.provider()))
                .findFirst().orElseThrow();
        assertEquals("primary", switchLog.fromProvider());
        assertEquals("secondary", switchLog.toProvider());
        assertEquals("TEMPORARY_UNAVAILABLE", switchLog.switchReason());
    }

    @Test
    void doesNotSwitchOnAuthInvalidAndDisablesProvider() {
        properties = new ProviderProperties();
        enable("primary", 10, 0);
        enable("secondary", 100, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.AUTH_INVALID, "401");
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        ProviderException error = assertThrows(ProviderException.class,
                () -> manager.recognize(fingerprintRequest("fp-3"), ctx));

        assertEquals(ProviderErrorCode.AUTH_INVALID, error.errorCode());
        assertEquals(0, secondary.calls.get());
        assertTrue(runtimeState.isDisabled("primary"));
    }

    @Test
    void switchesOnCapabilityLevelPermissionDenied() {
        properties = new ProviderProperties();
        enable("primary", 10, 0);
        enable("secondary", 100, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw new ProviderException("primary", ProviderErrorCode.PERMISSION_DENIED, "403",
                            com.musicbridge.provider.error.PermissionScope.CAPABILITY, "insufficient_scope",
                            403, null, null);
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-4"), ctx);

        assertEquals("secondary", result.providerId());
        assertFalse(runtimeState.isDisabled("primary"));
    }

    @Test
    void stopsSwitchingWhenBudgetIsExhausted() {
        properties = new ProviderProperties();
        enable("primary", 10, 0);
        enable("secondary", 100, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        RequestContext noSwitchCtx = RequestContext.withBudget("test-user", 5, 0);
        ProviderException error = assertThrows(ProviderException.class,
                () -> manager.recognize(fingerprintRequest("fp-5"), noSwitchCtx));

        assertEquals(ProviderErrorCode.TEMPORARY_UNAVAILABLE, error.errorCode());
        assertEquals(0, secondary.calls.get());
    }

    @Test
    void totalExternalCallBudgetCapsRetries() {
        properties = new ProviderProperties();
        enable("primary", 10, 3);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        manager = buildManager(List.of(primary));

        RequestContext smallBudget = RequestContext.withBudget("test-user", 2, 0);
        assertThrows(ProviderBudgetExhaustedException.class,
                () -> manager.recognize(fingerprintRequest("fp-6"), smallBudget));

        assertEquals(2, primary.calls.get());
    }

    @Test
    void opensCircuitAfterConsecutiveFailuresAndSkipsProvider() {
        properties = new ProviderProperties();
        properties.getPolicy().setCircuitFailureThreshold(3);
        enable("primary", 10, 0);
        enable("secondary", 100, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary));

        for (int i = 0; i < 3; i++) {
            int index = i;
            assertThrows(ProviderException.class,
                    () -> manager.recognize(fingerprintRequest("fp-circuit-" + index), ctx));
        }
        assertEquals(CircuitState.OPEN, circuitState("primary"));

        manager = buildManager(List.of(primary, secondary));
        RecognitionResult result = manager.recognize(fingerprintRequest("fp-circuit-after"), ctx);

        assertEquals("secondary", result.providerId());
        assertEquals(3, primary.calls.get());
    }

    @Test
    void cachesRecognitionResultForSameInput() {
        properties = new ProviderProperties();
        enable("primary", 10, 0);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("primary"));
        manager = buildManager(List.of(primary));

        RecognitionResult first = manager.recognize(fingerprintRequest("same-fp"), ctx);
        RecognitionResult second = manager.recognize(fingerprintRequest("same-fp"), ctx);

        assertEquals(first.providerId(), second.providerId());
        assertEquals(1, primary.calls.get());
        assertTrue(recorder.recent(10).stream().anyMatch(ProviderCallLog::cacheHit));
    }

    @Test
    void rateLimitedRetriesOnceAndSwitchesWhenRetryAfterExceedsThreshold() {
        properties = new ProviderProperties();
        enable("primary", 10, 3);
        enable("secondary", 100, 3);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.rateLimited("primary", "429", Duration.ofSeconds(30));
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-429-switch"), ctx);

        assertEquals("secondary", result.providerId());
        assertEquals(2, primary.calls.get());
    }

    @Test
    void rateLimitedDoesNotSwitchWhenRetryAfterIsShort() {
        properties = new ProviderProperties();
        enable("primary", 10, 3);
        enable("secondary", 100, 3);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.rateLimited("primary", "429", Duration.ofSeconds(2));
                });
        ScriptedRecognitionProvider secondary = new ScriptedRecognitionProvider("secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("secondary"));
        manager = buildManager(List.of(primary, secondary));

        ProviderException error = assertThrows(ProviderException.class,
                () -> manager.recognize(fingerprintRequest("fp-429-stop"), ctx));

        assertEquals(ProviderErrorCode.RATE_LIMITED, error.errorCode());
        assertEquals(2, primary.calls.get());
        assertEquals(0, secondary.calls.get());
    }

    @Test
    void retriesTemporaryFailureOnePlusThreeTimesAtMost() {
        properties = new ProviderProperties();
        enable("primary", 10, 3);
        ScriptedRecognitionProvider primary = new ScriptedRecognitionProvider("primary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT),
                () -> {
                    throw ProviderException.of("primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        manager = buildManager(List.of(primary));

        ProviderException error = assertThrows(ProviderException.class,
                () -> manager.recognize(fingerprintRequest("fp-retry-limit"), ctx));

        assertEquals(ProviderErrorCode.TEMPORARY_UNAVAILABLE, error.errorCode());
        assertEquals(4, primary.calls.get(), "首次请求 1 次 + 临时故障额外最多重试 3 次");
    }

    @Test
    void timesOutSlowProviderAndFallsBack() {
        properties = new ProviderProperties();
        enable("slow", 10, 0);
        properties.settingFor("slow").setTimeoutMs(50);
        enable("fast", 100, 0);
        ScriptedRecognitionProvider slow = new ScriptedRecognitionProvider("slow",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw ProviderException.temporary("slow", "interrupted", e);
                    }
                    return success("slow");
                });
        ScriptedRecognitionProvider fast = new ScriptedRecognitionProvider("fast",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("fast"));
        manager = buildManager(List.of(slow, fast));

        RecognitionResult result = manager.recognize(fingerprintRequest("fp-timeout"), ctx);

        assertEquals("fast", result.providerId());
        assertEquals(1, slow.calls.get(), "超时后不应无限等待，且只调用一次");
    }

    @Test
    void rawAudioFailureDoesNotFallBackToFingerprintOnlyProvider() {
        properties = new ProviderProperties();
        enable("raw-primary", 10, 0);
        enable("fingerprint-secondary", 100, 0);
        ScriptedRecognitionProvider rawPrimary = new ScriptedRecognitionProvider("raw-primary",
                Set.of(RecognitionInputType.RAW_AUDIO),
                () -> {
                    throw ProviderException.of("raw-primary", ProviderErrorCode.TEMPORARY_UNAVAILABLE, "503");
                });
        ScriptedRecognitionProvider fingerprintOnly = new ScriptedRecognitionProvider("fingerprint-secondary",
                Set.of(RecognitionInputType.AUDIO_FINGERPRINT), () -> success("fingerprint-secondary"));
        manager = buildManager(List.of(rawPrimary, fingerprintOnly));

        ProviderException error = assertThrows(ProviderException.class,
                () -> manager.recognize(new RecognitionRequest(RecognitionInputType.RAW_AUDIO,
                        "clip-1", null, Duration.ofSeconds(5), "consent-1", "zh"), ctx));

        assertEquals(ProviderErrorCode.TEMPORARY_UNAVAILABLE, error.errorCode());
        assertEquals(1, rawPrimary.calls.get());
        assertEquals(0, fingerprintOnly.calls.get(), "RAW_AUDIO 不得降级到仅指纹 Provider");
    }

    @Test
    void mockProvidersCoverAllCapabilitiesThroughManager() {
        properties = new ProviderProperties();
        for (String id : List.of("mock-recognition", "mock-metadata", "mock-lyrics",
                "mock-translation", "mock-transliteration")) {
            enable(id, 10, 0);
        }
        manager = buildManager(List.of(new MockRecognitionProvider(), new MockMetadataProvider(),
                new MockLyricsProvider(), new MockTranslationProvider(), new MockTransliterationProvider()));

        RecognitionResult recognition = manager.recognize(
                new RecognitionRequest(RecognitionInputType.RAW_AUDIO, "clip-1", null,
                        Duration.ofSeconds(5), "consent-1", "zh"), ctx);
        MetadataRecord metadata = manager.lookupMetadata(MetadataQuery.byTitle("晴天", List.of("周杰伦"), "TW"), ctx);
        LyricsDTO lyrics = manager.fetchLyrics(new LyricsQuery(null, "晴天", "周杰伦", null,
                Duration.ofSeconds(269), "zh", true), ctx);
        TranslationResult translation = manager.translate(
                new TranslationRequest(List.of("晴天"), "zh", "en", "mock-v1"), ctx);
        TransliterationResult transliteration = manager.transliterate(
                new TransliterationRequest(List.of("晴天"), "zh", TransliterationScheme.PINYIN), ctx);

        assertTrue(recognition.hasMatch());
        assertTrue(metadata.hasMatch());
        assertTrue(lyrics.hasMatch());
        assertTrue(translation.hasMatch());
        assertTrue(transliteration.hasMatch());
        assertNotNull(translation.providerId());
    }

    // ---------------------------------------------------------------- 测试辅助

    private CircuitState circuitState(String providerId) {
        return circuitRegistry.state(providerId);
    }

    private void enable(String providerId, int priority, int maxRetries) {
        ProviderProperties.ProviderSetting setting = new ProviderProperties.ProviderSetting();
        setting.setEnabled(true);
        setting.setPriority(priority);
        setting.setMaxRetries(maxRetries);
        setting.setRequestsPerSecond(1000);
        setting.setBurstCapacity(1000);
        setting.setTimeoutMs(2000);
        properties.getEntries().put(providerId, setting);
    }

    private ProviderManager buildManager(List<MusicProvider> providers) {
        ProviderRegistry registry = new ProviderRegistry(providers, properties, new MockEnvironment());
        if (circuitRegistry == null) {
            circuitRegistry = new CircuitBreakerRegistry(properties, clock);
        }
        CacheProperties cacheProperties = new CacheProperties();
        return new ProviderManager(registry, properties, new CaffeineProviderQueryCache(cacheProperties),
                new CacheTtlPolicy(cacheProperties), new SingleFlight(), new RateLimiterRegistry(properties), callExecutor,
                circuitRegistry, runtimeState, recorder,
                new ProviderUsageStats(), new DefaultCostEstimator(properties), Sleeper.noop(), clock);
    }

    private RecognitionRequest fingerprintRequest(String fingerprint) {
        return new RecognitionRequest(RecognitionInputType.AUDIO_FINGERPRINT, null, fingerprint,
                Duration.ofSeconds(10), null, "zh");
    }

    private static RecognitionResult success(String providerId) {
        return new RecognitionResult(List.of(new RecognitionCandidate("晴天", List.of("周杰伦"),
                "叶惠美", Duration.ofSeconds(269), null, 0.95, Map.of("MBID", "mock-1"), null)),
                providerId, Instant.now());
    }

    /**
     * 可编排行为的识别 Provider，用于路由/降级/熔断测试。
     */
    private static final class ScriptedRecognitionProvider implements RecognitionProvider {

        private final String id;
        private final RecognitionCapabilities capabilities;
        private final Supplier<RecognitionResult> script;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedRecognitionProvider(String id, Set<RecognitionInputType> inputTypes,
                                            Supplier<RecognitionResult> script) {
            this.id = id;
            this.capabilities = new DefaultRecognitionCapabilities(inputTypes, true, true, false);
            this.script = script;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ProviderCapability capability() {
            return ProviderCapability.RECOGNITION;
        }

        @Override
        public RecognitionCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public RecognitionResult recognize(RecognitionRequest request, RequestContext ctx) {
            calls.incrementAndGet();
            return script.get();
        }

        @Override
        public ProviderConfigStatus checkConfig() {
            return ProviderConfigStatus.ok();
        }

        @Override
        public ProviderHealth probeHealth() {
            return ProviderHealth.ok("test");
        }
    }
}
