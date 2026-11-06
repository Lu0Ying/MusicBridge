package com.musicbridge.security;

import java.time.Instant;

/**
 * 上传授权记录，对应 upload_consent 表。
 */
public record UploadConsentRecord(String id,
                                  String userId,
                                  String scope,
                                  String purpose,
                                  Instant grantedAt,
                                  Instant revokedAt,
                                  Instant expiresAt,
                                  int retentionDays) {
}
