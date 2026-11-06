package com.musicbridge.provider.observability;

import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.error.ProviderErrorCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Provider 调用日志（报告 8.9）。
 * <p>不记录密钥与音频内容；指纹只允许记录哈希前缀，日志中不出现原文。</p>
 */
public record ProviderCallLog(String traceId,
                              String provider,
                              ProviderCapability capability,
                              String inputType,
                              int requestUnits,
                              Integer httpStatus,
                              String providerErrorCode,
                              ProviderErrorCode normalizedErrorCode,
                              int retryCount,
                              Duration retryAfter,
                              long latencyMs,
                              String fromProvider,
                              String toProvider,
                              String switchReason,
                              BigDecimal estimatedCost,
                              boolean cacheHit,
                              Instant occurredAt) {
}
