package com.musicbridge.clips;

import java.time.Instant;

/**
 * 片段的持久化元数据；不包含绝对存储路径。
 *
 * @param id          片段 UUID
 * @param userId      归属用户（只来自认证上下文）
 * @param consentId   绑定的授权 ID
 * @param format      归一化格式名
 * @param contentType 落库 MIME
 * @param sizeBytes   文件实际字节数
 * @param durationMs  服务端实测时长
 * @param fileName    展示用原始文件名（可为空）
 * @param storageName 落盘文件名（{@code clipId.ext}）
 * @param status      {@link ClipStatus}
 * @param createdAt   创建时间
 * @param expiresAt   过期时间
 */
public record ClipRecord(
        String id,
        String userId,
        String consentId,
        String format,
        String contentType,
        long sizeBytes,
        long durationMs,
        String fileName,
        String storageName,
        ClipStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public ClipRecord asExpired() {
        return new ClipRecord(id, userId, consentId, format, contentType, sizeBytes, durationMs,
                fileName, storageName, ClipStatus.EXPIRED, createdAt, expiresAt);
    }
}
