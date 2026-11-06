package com.musicbridge.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 上传授权服务：创建、查询、撤销，以及上传链路使用前的有效性校验。
 *
 * <p>所有方法都以认证上下文中的 userId 为准；跨用户访问统一按 404 处理。
 */
@Service
public class ConsentService {

    private final ConsentRepository repository;
    private final Clock clock;

    public ConsentService(ConsentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ConsentDTO create(String userId, ConsentCreateRequest request) {
        Instant now = clock.instant();
        UploadConsentRecord record = new UploadConsentRecord(
                UUID.randomUUID().toString(),
                userId,
                request.scope(),
                request.purpose(),
                now,
                null,
                request.expiresAt(),
                request.retentionDays());
        repository.insert(record);
        return toDto(record, now);
    }

    public List<ConsentDTO> list(String userId) {
        Instant now = clock.instant();
        return repository.findByUserId(userId).stream()
                .map(record -> toDto(record, now))
                .toList();
    }

    public ConsentDTO get(String userId, String id) {
        return toDto(requireOwned(userId, id), clock.instant());
    }

    public ConsentDTO revoke(String userId, String id) {
        Instant now = clock.instant();
        UploadConsentRecord existing = requireOwned(userId, id);
        if (existing.revokedAt() == null) {
            repository.revoke(id, userId, now);
            existing = requireOwned(userId, id);
        }
        return toDto(existing, now);
    }

    /**
     * 上传前校验授权是否可继续使用；撤销或过期都返回 403。
     */
    public UploadConsentRecord requireActive(String userId, String id) {
        UploadConsentRecord record = requireOwned(userId, id);
        if (record.revokedAt() != null) {
            throw new AccessForbiddenException("上传授权已撤销");
        }
        if (record.expiresAt() != null && !record.expiresAt().isAfter(clock.instant())) {
            throw new AccessForbiddenException("上传授权已过期");
        }
        return record;
    }

    private UploadConsentRecord requireOwned(String userId, String id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("上传授权不存在"));
    }

    private ConsentDTO toDto(UploadConsentRecord record, Instant now) {
        boolean active = record.revokedAt() == null
                && (record.expiresAt() == null || record.expiresAt().isAfter(now));
        return new ConsentDTO(record.id(), record.scope(), record.purpose(), record.grantedAt(),
                record.revokedAt(), record.expiresAt(), record.retentionDays(), active);
    }

    public record ConsentCreateRequest(
            @jakarta.validation.constraints.NotBlank String scope,
            @jakarta.validation.constraints.NotBlank String purpose,
            @jakarta.validation.constraints.Min(0) int retentionDays,
            Instant expiresAt) {
    }

    public record ConsentDTO(String id,
                             String scope,
                             String purpose,
                             Instant grantedAt,
                             Instant revokedAt,
                             Instant expiresAt,
                             int retentionDays,
                             boolean active) {
    }
}
