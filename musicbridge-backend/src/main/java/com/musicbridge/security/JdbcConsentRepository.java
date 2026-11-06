package com.musicbridge.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MySQL upload_consent 表的授权存储。
 */
@Repository
public class JdbcConsentRepository implements ConsentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConsentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(UploadConsentRecord consent) {
        jdbcTemplate.update(
                "INSERT INTO upload_consent (id, user_id, scope, purpose, granted_at, revoked_at, expires_at, "
                        + "retention_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                consent.id(),
                consent.userId(),
                consent.scope(),
                consent.purpose(),
                Timestamp.from(consent.grantedAt()),
                consent.revokedAt() == null ? null : Timestamp.from(consent.revokedAt()),
                consent.expiresAt() == null ? null : Timestamp.from(consent.expiresAt()),
                consent.retentionDays());
    }

    @Override
    public Optional<UploadConsentRecord> findByIdAndUserId(String id, String userId) {
        return jdbcTemplate.query(
                        "SELECT id, user_id, scope, purpose, granted_at, revoked_at, expires_at, retention_days "
                                + "FROM upload_consent WHERE id = ? AND user_id = ?",
                        (rs, rowNum) -> map(rs),
                        id, userId)
                .stream()
                .findFirst();
    }

    @Override
    public List<UploadConsentRecord> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT id, user_id, scope, purpose, granted_at, revoked_at, expires_at, retention_days "
                        + "FROM upload_consent WHERE user_id = ? ORDER BY granted_at DESC",
                (rs, rowNum) -> map(rs),
                userId);
    }

    @Override
    public boolean revoke(String id, String userId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE upload_consent SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), id, userId) > 0;
    }

    private UploadConsentRecord map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new UploadConsentRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("scope"),
                rs.getString("purpose"),
                rs.getTimestamp("granted_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getInt("retention_days"));
    }
}
