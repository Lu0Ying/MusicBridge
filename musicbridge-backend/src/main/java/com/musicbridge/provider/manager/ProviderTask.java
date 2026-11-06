package com.musicbridge.provider.manager;

import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.cache.CacheCategory;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Provider 调用任务描述：由各能力方法构造，ProviderManager 统一执行。
 */
public record ProviderTask<T>(String operation,
                              ProviderCapability capability,
                              String inputType,
                              Predicate<MusicProvider> providerFilter,
                              BiFunction<MusicProvider, RequestContext, T> invocation,
                              Predicate<T> matchPredicate,
                              Supplier<T> emptyResult,
                              String cacheKey,
                              CacheCategory cacheCategory,
                              int requestUnits) {
}
