package com.musicbridge.web.clips;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 片段元数据响应（契约草案，见 research/musicbridge-phase1/CLIPS_CONTRACT.md）。
 *
 * <p>不含 userId 与存储路径等内部信息；{@code durationMs} 为服务端实测值。</p>
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
