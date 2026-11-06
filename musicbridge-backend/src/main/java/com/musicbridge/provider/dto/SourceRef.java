package com.musicbridge.provider.dto;

import java.time.Instant;

/**
 * 外部数据来源四件套（provider / recordId / license / retrievedAt）。
 * <p>所有外部数据与缓存条目必须保留来源与许可，便于追踪与合规展示。</p>
 */
public record SourceRef(String provider, String recordId, String license, Instant retrievedAt) {
}
