package com.musicbridge.security;

import java.time.Instant;

/**
 * 会话持久化记录；token 原文不落库，只保存哈希。
 */
public record SessionRecord(String id,
                            String userId,
                            String tokenHash,
                            Instant createdAt,
                            Instant expiresAt,
                            Instant revokedAt) {
}
