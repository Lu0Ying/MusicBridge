package com.musicbridge.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 上传授权存储抽象；所有查询都带 userId，避免跨用户读取。
 */
public interface ConsentRepository {

    void insert(UploadConsentRecord consent);

    Optional<UploadConsentRecord> findByIdAndUserId(String id, String userId);

    List<UploadConsentRecord> findByUserId(String userId);

    boolean revoke(String id, String userId, Instant now);
}
