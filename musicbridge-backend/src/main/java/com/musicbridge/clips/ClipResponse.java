package com.musicbridge.clips;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 片段元数据响应；不含 userId 与存储路径等内部信息，{@code durationMs} 为服务端实测值。
 */
@Schema(name = "ClipResponse", description = "片段元数据")
public record ClipResponse(
        UUID clipId,
        UUID consentId,
        String format,
        String contentType,
        long sizeBytes,
        int durationMs,
        String fileName,
        Instant createdAt,
        Instant expiresAt,
        int retentionDays,
        ClipStatus status) {
}
