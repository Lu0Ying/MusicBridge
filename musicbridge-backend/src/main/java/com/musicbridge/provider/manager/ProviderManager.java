package com.musicbridge.provider.manager;

import com.musicbridge.common.RequestContext;
import com.musicbridge.config.ProviderProperties;
import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.cache.CacheCategory;
import com.musicbridge.provider.cache.CacheEntry;
import com.musicbridge.provider.cache.CacheKeys;
import com.musicbridge.provider.cache.CacheOrigin;
import com.musicbridge.provider.cache.ProviderQueryCache;
import com.musicbridge.provider.cache.SingleFlight;
import com.musicbridge.provider.circuit.CircuitBreaker;
import com.musicbridge.provider.circuit.CircuitBreakerRegistry;
import com.musicbridge.provider.circuit.CircuitState;
import com.musicbridge.provider.dto.MetadataRecord;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.error.ProviderErrorCode;
import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.lyrics.LyricLineDTO;
import com.musicbridge.provider.lyrics.LyricsDTO;
import com.musicbridge.provider.lyrics.LyricsKind;
import com.musicbridge.provider.lyrics.LyricsProvider;
import com.musicbridge.provider.lyrics.LyricsQuery;
import com.musicbridge.provider.metadata.MetadataProvider;
import com.musicbridge.provider.metadata.MetadataQuery;
import com.musicbridge.provider.observability.CostEstimator;
import com.musicbridge.provider.observability.ProviderCallLog;
import com.musicbridge.provider.observability.ProviderCallLogRecorder;
import com.musicbridge.provider.observability.ProviderUsageStats;
import com.musicbridge.provider.ratelimit.RateLimiterRegistry;
import com.musicbridge.provider.recognition.RecognitionInputType;
import com.musicbridge.provider.recognition.RecognitionProvider;
import com.musicbridge.provider.recognition.RecognitionRequest;
import com.musicbridge.provider.recognition.RecognitionResult;
import com.musicbridge.provider.registry.ProviderRegistry;
import com.musicbridge.provider.translation.TranslationProvider;
import com.musicbridge.provider.translation.TranslationRequest;
import com.musicbridge.provider.translation.TranslationResult;
import com.musicbridge.provider.transliteration.TransliterationProvider;
import com.musicbridge.provider.transliteration.TransliterationRequest;
import com.musicbridge.provider.transliteration.TransliterationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * ProviderManager：负责选择、重试、降级、熔断、限流、缓存与计量。
 * <p>业务层只调用本类的按能力方法，不出现具体 Provider 分支判断。
 * 选择顺序：supports/能力过滤 → 启用/优先级 → 健康/熔断 → 预算 → 调用（报告 8.7）。</p>
 */
@Component
public class ProviderManager {

    private static final Logger log = LoggerFactory.getLogger(ProviderManager.class);

    private final ProviderRegistry registry;
    private final ProviderProperties properties;
    private final ProviderQueryCache cache;
    private final SingleFlight singleFlight;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final ProviderCallExecutor callExecutor;
    private final CircuitBreakerRegistry circuitRegistry;
    private final ProviderRuntimeState runtimeState;
    private final ProviderCallLogRecorder callLogRecorder;
    private final ProviderUsageStats usageStats;
    private final CostEstimator costEstimator;
    private final Sleeper sleeper;
    private final Clock clock;

    public ProviderManager(ProviderRegistry registry,
                           ProviderProperties properties,
                           ProviderQueryCache cache,
                           SingleFlight singleFlight,
                           RateLimiterRegistry rateLimiterRegistry,
                           ProviderCallExecutor callExecutor,
                           CircuitBreakerRegistry circuitRegistry,
                           ProviderRuntimeState runtimeState,
                           ProviderCallLogRecorder callLogRecorder,
                           ProviderUsageStats usageStats,
                           CostEstimator costEstimator,
                           Sleeper sleeper,
                           Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.cache = cache;
        this.singleFlight = singleFlight;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.callExecutor = callExecutor;
        this.circuitRegistry = circuitRegistry;
        this.runtimeState = runtimeState;
        this.callLogRecorder = callLogRecorder;
        this.usageStats = usageStats;
        this.costEstimator = costEstimator;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 按能力入口

    public RecognitionResult recognize(RecognitionRequest request, RequestContext ctx) {
        String cacheKey = "recognition:" + request.inputType().name() + ":"
                + CacheKeys.sha256Prefix(RecognitionInputType.RAW_AUDIO == request.inputType()
                ? request.clipId() : request.fingerprint(), 16);
        ProviderTask<RecognitionResult> task = new ProviderTask<>(
                "recognize", ProviderCapability.RECOGNITION, request.inputType().name(),
                provider -> provider instanceof RecognitionProvider recognitionProvider
                        && recognitionProvider.supports(request.inputType()),
                (provider, context) -> ((RecognitionProvider) provider).recognize(request, context),
                RecognitionResult::hasMatch,
                () -> new RecognitionResult(List.of(), "none", clock.instant()),
                cacheKey, CacheCategory.RECOGNITION, 1);
        return execute(task, ctx);
    }

    public MetadataRecord lookupMetadata(MetadataQuery query, RequestContext ctx) {
        String cacheKey = "metadata:" + CacheKeys.sha256Prefix(metadataKeySource(query), 16);
        ProviderTask<MetadataRecord> task = new ProviderTask<>(
                "metadata", ProviderCapability.METADATA, null,
                provider -> provider instanceof MetadataProvider,
                (provider, context) -> ((MetadataProvider) provider).lookup(query, context),
                MetadataRecord::hasMatch,
                () -> new MetadataRecord(null, null, List.of(), List.of(), List.of(), null, null),
                cacheKey, CacheCategory.METADATA, 1);
        return execute(task, ctx);
    }

    public LyricsDTO fetchLyrics(LyricsQuery query, RequestContext ctx) {
        CacheCategory category = query.preferSynced() ? CacheCategory.LYRICS_SYNCED : CacheCategory.LYRICS_PLAIN;
        String cacheKey = "lyrics:" + CacheKeys.sha256Prefix(lyricsKeySource(query), 16);
        ProviderTask<LyricsDTO> task = new ProviderTask<>(
                "lyrics", ProviderCapability.LYRICS, null,
                provider -> provider instanceof LyricsProvider,
                (provider, context) -> ((LyricsProvider) provider).fetch(query, context),
                LyricsDTO::hasMatch,
                () -> new LyricsDTO(null, null, LyricsKind.ORIGINAL, query.language(), null,
                        List.<LyricLineDTO>of(), null),
                cacheKey, category, 1);
        return execute(task, ctx);
    }

    public TranslationResult translate(TranslationRequest request, RequestContext ctx) {
        String cacheKey = "translation:" + CacheKeys.sha256Prefix(
                String.join("\n", request.lines()) + "|" + request.sourceLanguage() + "|"
                        + request.targetLanguage() + "|" + request.modelVersion(), 16);
        ProviderTask<TranslationResult> task = new ProviderTask<>(
                "translate", ProviderCapability.TRANSLATION, null,
                provider -> provider instanceof TranslationProvider,
                (provider, context) -> ((TranslationProvider) provider).translate(request, context),
                TranslationResult::hasMatch,
                () -> new TranslationResult(List.of(), request.sourceLanguage(), request.targetLanguage(), "none", null),
                cacheKey, CacheCategory.TRANSLATION, request.lines().size());
        return execute(task, ctx);
    }

    public TransliterationResult transliterate(TransliterationRequest request, RequestContext ctx) {
        String cacheKey = "transliteration:" + CacheKeys.sha256Prefix(
                String.join("\n", request.lines()) + "|" + request.sourceLanguage() + "|" + request.scheme(), 16);
        ProviderTask<TransliterationResult> task = new ProviderTask<>(
                "transliterate", ProviderCapability.TRANSLITERATION, null,
                provider -> provider instanceof TransliterationProvider,
                (provider, context) -> ((TransliterationProvider) provider).transliterate(request, context),
                TransliterationResult::hasMatch,
                () -> new TransliterationResult(List.of(), request.scheme(), "none", null),
                cacheKey, CacheCategory.TRANSLITERATION, request.lines().size());
        return execute(task, ctx);
    }

    // ---------------------------------------------------------------- 核心编排

    public <T> T execute(ProviderTask<T> task, RequestContext ctx) {
        if (task.cacheKey() == null) {
            return executeSingle(task, ctx);
        }
        // 同一缓存键并发只执行一次，避免缓存击穿造成重复计费调用
        return singleFlight.run(task.cacheKey(), () -> executeSingle(task, ctx));
    }

    private <T> T executeSingle(ProviderTask<T> task, RequestContext ctx) {
        Instant now = clock.instant();
        CacheEntry staleEntry = null;
        if (task.cacheKey() != null) {
            Optional<CacheEntry> cached = cache.get(task.cacheKey());
            if (cached.isPresent()) {
                CacheEntry entry = cached.get();
                if (entry.usableAt(now)) {
                    recordCacheHit(entry, task, ctx, false);
                    return valueOf(entry);
                }
                if (!entry.category().negative() && entry.staleUsableAt(now)) {
                    staleEntry = entry;
                }
            }
        }

        List<MusicProvider> candidates = selectCandidates(task, now);
        if (candidates.isEmpty()) {
            if (staleEntry != null) {
                return returnStale(staleEntry, task, ctx);
            }
            throw ProviderException.of(null, ProviderErrorCode.NO_MATCH,
                    "没有可用 Provider: " + task.operation());
        }

        ProviderException lastError = null;
        T lastEmptyResult = null;
        MusicProvider previousProvider = null;

        for (MusicProvider provider : candidates) {
            boolean switching = previousProvider != null;
            if (switching && !ctx.budget().tryConsumeSwitch()) {
                log.debug("切换预算耗尽，停止降级: trace={}", ctx.traceId());
                break;
            }
            Attempt<T> attempt = attempt(provider, task, ctx,
                    switching ? previousProvider.id() : null, switching ? lastError : null);
            previousProvider = provider;

            if (attempt.error() == null) {
                cacheResult(task, provider, attempt.value());
                return attempt.value();
            }
            lastError = attempt.error();
            if (lastError.errorCode() == ProviderErrorCode.NO_MATCH && attempt.value() != null) {
                lastEmptyResult = attempt.value();
            }
            handleFailure(provider, lastError);
            if (!shouldSwitch(lastError)) {
                break;
            }
        }

        if (lastEmptyResult != null && task.cacheKey() != null) {
            cacheNegative(task, lastEmptyResult);
        }
        if (staleEntry != null) {
            return returnStale(staleEntry, task, ctx);
        }
        if (lastError == null) {
            throw ProviderException.of(null, ProviderErrorCode.NO_MATCH,
                    "所有 Provider 均无可用结果: " + task.operation());
        }
        throw lastError;
    }

    /**
     * 选择候选：能力/输入过滤 → 启用与优先级 → 运行时停用/冷却 → 静态配置 → 熔断。
     */
    private List<MusicProvider> selectCandidates(ProviderTask<?> task, Instant now) {
        List<MusicProvider> result = new ArrayList<>();
        for (MusicProvider provider : registry.enabledCandidates(task.capability())) {
            if (!task.providerFilter().test(provider)) {
                continue;
            }
            if (runtimeState.isDisabled(provider.id())) {
                continue;
            }
            if (runtimeState.isProviderCoolingDown(provider.id(), now)
                    || runtimeState.isCapabilityCoolingDown(provider.id(), task.capability(), now)) {
                continue;
            }
            if (circuitRegistry.state(provider.id()) == CircuitState.OPEN) {
                continue;
            }
            if (!provider.checkConfig().ready()) {
                runtimeState.disableProvider(provider.id(),
                        "CONFIG_ERROR: " + provider.checkConfig().missingItems());
                log.error("Provider {} 静态配置不完整，已停用: {}", provider.id(),
                        provider.checkConfig().missingItems());
                continue;
            }
            result.add(provider);
        }
        return result;
    }

    /**
     * 单个 Provider 的重试循环：每次外部调用都计入全局预算并写调用日志。
     */
    private <T> Attempt<T> attempt(MusicProvider provider, ProviderTask<T> task, RequestContext ctx,
                                   String fromProvider, ProviderException previousError) {
        ProviderProperties.ProviderSetting setting = properties.settingFor(provider.id());
        ProviderProperties.ProviderPolicy policy = properties.getPolicy();
        int maxRetries = Math.min(setting.getMaxRetries(), policy.getMaxRetries());

        CircuitBreaker breaker = circuitRegistry.breaker(provider.id());
        if (!breaker.tryAcquire()) {
            ProviderException error = ProviderException.of(provider.id(),
                    ProviderErrorCode.TEMPORARY_UNAVAILABLE, "熔断器打开，跳过该 Provider");
            recordCallLog(provider, task, ctx, 0, error, 0, fromProvider, previousError, BigDecimal.ZERO, false);
            return new Attempt<>(null, false, error, 0);
        }

        int retry = 0;
        while (true) {
            if (!ctx.budget().tryConsumeCall()) {
                throw new ProviderBudgetExhaustedException("外部调用预算耗尽: trace=" + ctx.traceId());
            }
            if (!rateLimiterRegistry.tryAcquire(provider.id())) {
                Duration cooldown = Duration.ofMillis(policy.getDefaultRetryAfterMs());
                ProviderException error = ProviderException.rateLimited(provider.id(),
                        "本地限流器拒绝调用", cooldown);
                recordCallLog(provider, task, ctx, 0, error, retry, fromProvider, previousError,
                        BigDecimal.ZERO, false);
                usageStats.recordFailure(provider.id(), 0, BigDecimal.ZERO);
                return new Attempt<>(null, false, error, retry);
            }

            long startNanos = System.nanoTime();
            BigDecimal cost = costEstimator.estimate(provider.id(), task.requestUnits());
            try {
                T value = callExecutor.call(Duration.ofMillis(setting.getTimeoutMs()),
                        () -> task.invocation().apply(provider, ctx));
                long latencyMs = elapsedMs(startNanos);
                boolean matched = task.matchPredicate().test(value);
                ProviderException outcome = matched ? null
                        : ProviderException.noMatch(provider.id(), "Provider 返回空结果");
                recordCallLog(provider, task, ctx, latencyMs, outcome, retry, fromProvider, previousError,
                        cost, false);
                usageStats.recordSuccess(provider.id(), latencyMs, cost);
                breaker.onSuccess();
                return new Attempt<>(value, matched, outcome, retry);
            } catch (TimeoutException e) {
                ProviderException error = ProviderException.temporary(provider.id(),
                        "调用超时: " + setting.getTimeoutMs() + "ms", e);
                recordCallLog(provider, task, ctx, elapsedMs(startNanos), error, retry, fromProvider,
                        previousError, cost, false);
                usageStats.recordFailure(provider.id(), elapsedMs(startNanos), cost);
                if (!canRetry(error, retry, maxRetries)) {
                    return new Attempt<>(null, false, error, retry);
                }
                sleeper.sleep(backoff(error, retry));
                retry++;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                ProviderException error = cause instanceof ProviderException providerException
                        ? providerException
                        : ProviderException.temporary(provider.id(),
                        "调用异常: " + (cause == null ? e.getMessage() : cause.getMessage()), cause);
                recordCallLog(provider, task, ctx, elapsedMs(startNanos), error, retry, fromProvider,
                        previousError, cost, false);
                usageStats.recordFailure(provider.id(), elapsedMs(startNanos), cost);
                if (!canRetry(error, retry, maxRetries)) {
                    return new Attempt<>(null, false, error, retry);
                }
                sleeper.sleep(backoff(error, retry));
                retry++;
            }
        }
    }

    /**
     * 错误处理：停用、能力冷却、配额冷却与熔断计数。
     */
    private void handleFailure(MusicProvider provider, ProviderException error) {
        Instant now = clock.instant();
        ProviderProperties.ProviderPolicy policy = properties.getPolicy();
        switch (error.errorCode()) {
            case CONFIG_ERROR, AUTH_INVALID ->
                    runtimeState.disableProvider(provider.id(), error.errorCode() + ": " + error.getMessage());
            case PERMISSION_DENIED -> {
                if (error.isAccountLevelPermissionDenied()) {
                    runtimeState.disableProvider(provider.id(), "PERMISSION_DENIED(ACCOUNT)");
                } else {
                    runtimeState.cooldownCapability(provider.id(), provider.capability(),
                            now.plusMillis(policy.getCapabilityCooldownMs()));
                }
            }
            case QUOTA_EXCEEDED -> runtimeState.cooldownProvider(provider.id(),
                    now.plusMillis(policy.getQuotaCooldownMs()));
            case RATE_LIMITED -> {
                Duration cooldown = error.retryAfter() != null
                        ? error.retryAfter() : Duration.ofMillis(policy.getDefaultRetryAfterMs());
                runtimeState.cooldownProvider(provider.id(), now.plus(cooldown));
                circuitRegistry.breaker(provider.id()).onFailure();
            }
            case TEMPORARY_UNAVAILABLE -> circuitRegistry.breaker(provider.id()).onFailure();
            default -> {
                // NO_MATCH / UNSUPPORTED_INPUT 不熔断、不停用
            }
        }
    }

    /**
     * 是否允许跨 Provider 降级：仅同输入类型、同能力且授权兼容的 Provider 可切换。
     */
    private boolean shouldSwitch(ProviderException error) {
        return switch (error.errorCode()) {
            case CONFIG_ERROR, AUTH_INVALID -> false;
            case PERMISSION_DENIED -> !error.isAccountLevelPermissionDenied();
            case RATE_LIMITED -> {
                Duration retryAfter = error.retryAfter();
                yield retryAfter != null
                        && retryAfter.toMillis() > properties.getPolicy().getRetryAfterSwitchThresholdMs();
            }
            case QUOTA_EXCEEDED, TEMPORARY_UNAVAILABLE, UNSUPPORTED_INPUT, NO_MATCH -> true;
        };
    }

    /**
     * 重试口径：首次请求 1 次；临时故障最多额外重试 3 次；429 只额外重试 1 次。
     * 所有尝试同时受 {@link RequestContext} 的单任务外部调用总预算约束。
     */
    private boolean canRetry(ProviderException error, int retry, int maxRetries) {
        return switch (error.errorCode()) {
            case TEMPORARY_UNAVAILABLE -> retry < maxRetries;
            case RATE_LIMITED -> retry == 0;
            default -> false;
        };
    }

    /**
     * 退避：429 遵守 Retry-After 并加 ≤30% 抖动；5xx 指数退避。
     * 429 只重试一次，由调用方的 retry 计数与 maxRetries 约束。
     */
    private Duration backoff(ProviderException error, int retry) {
        Duration base;
        if (error.errorCode() == ProviderErrorCode.RATE_LIMITED) {
            if (retry > 0) {
                return Duration.ZERO;
            }
            base = error.retryAfter() != null
                    ? error.retryAfter()
                    : Duration.ofMillis(properties.getPolicy().getDefaultRetryAfterMs());
        } else {
            base = Duration.ofMillis(properties.getPolicy().getRetryBaseMs() * (1L << Math.min(retry, 5)));
        }
        long jitterMs = (long) (base.toMillis() * ThreadLocalRandom.current().nextDouble(0.3));
        return base.plusMillis(jitterMs);
    }

    // ---------------------------------------------------------------- 缓存与日志

    private <T> T returnStale(CacheEntry entry, ProviderTask<T> task, RequestContext ctx) {
        recordCacheHit(entry, task, ctx, true);
        return valueOf(entry);
    }

    private <T> void cacheResult(ProviderTask<T> task, MusicProvider provider, T value) {
        if (task.cacheKey() == null || task.cacheCategory() == null) {
            return;
        }
        Instant now = clock.instant();
        Duration ttl = task.cacheCategory().ttl();
        cache.put(new CacheEntry(task.cacheKey(), task.cacheCategory(), value,
                new SourceRef(provider.id(), null, null, now), now,
                now.plus(ttl), now.plus(ttl).plus(Duration.ofDays(7)),
                CacheOrigin.PROVIDER, false, List.of(), "v1"));
    }

    private <T> void cacheNegative(ProviderTask<T> task, T value) {
        Instant now = clock.instant();
        Duration ttl = CacheCategory.NEGATIVE.ttl();
        cache.put(new CacheEntry(task.cacheKey(), CacheCategory.NEGATIVE, value,
                new SourceRef("none", null, null, now), now,
                now.plus(ttl), now.plus(ttl), CacheOrigin.PROVIDER, false, List.of(), "v1"));
    }

    private <T> void recordCacheHit(CacheEntry entry, ProviderTask<T> task, RequestContext ctx, boolean stale) {
        String providerId = entry.source() == null ? "cache" : entry.source().provider();
        callLogRecorder.record(new ProviderCallLog(ctx.traceId(), providerId, task.capability(),
                task.inputType(), task.requestUnits(), null, null, null, 0, null, 0,
                null, null, stale ? "STALE_CACHE" : null, BigDecimal.ZERO, true, clock.instant()));
    }

    private <T> void recordCallLog(MusicProvider provider, ProviderTask<T> task, RequestContext ctx,
                                   long latencyMs, ProviderException error, int retryCount,
                                   String fromProvider, ProviderException previousError,
                                   BigDecimal cost, boolean cacheHit) {
        callLogRecorder.record(new ProviderCallLog(
                ctx.traceId(), provider.id(), task.capability(), task.inputType(), task.requestUnits(),
                error == null ? null : error.httpStatus(),
                error == null ? null : error.providerErrorCode(),
                error == null ? null : error.errorCode(),
                retryCount,
                error == null ? null : error.retryAfter(),
                latencyMs,
                fromProvider,
                fromProvider == null ? null : provider.id(),
                previousError == null ? null : previousError.errorCode().name(),
                cost,
                cacheHit,
                clock.instant()));
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private <T> T valueOf(CacheEntry entry) {
        return (T) entry.value();
    }

    private String metadataKeySource(MetadataQuery query) {
        return String.valueOf(query.recordingMbid()) + "|" + query.workMbid() + "|" + query.releaseMbid()
                + "|" + query.title() + "|" + String.join(",", query.artists()) + "|" + query.country();
    }

    private String lyricsKeySource(LyricsQuery query) {
        return String.valueOf(query.recordingMbid()) + "|" + query.title() + "|" + query.artist()
                + "|" + query.album() + "|" + query.language() + "|" + query.preferSynced();
    }

    /**
     * 单次手持尝试的结果。
     */
    private record Attempt<T>(T value, boolean matched, ProviderException error, int retryCount) {
    }
}
