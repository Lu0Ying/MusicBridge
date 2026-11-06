package com.musicbridge.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基于 MySQL user_session 表的会话存储。
 */
@Repository
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(SessionRecord session) {
        jdbcTemplate.update(
                "INSERT INTO user_session (id, user_id, token_hash, created_at, expires_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                session.id(),
                session.userId(),
                session.tokenHash(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.expiresAt()),
                session.revokedAt() == null ? null : Timestamp.from(session.revokedAt()));
    }

    @Override
    public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
        return jdbcTemplate.query(
                        "SELECT id, user_id, token_hash, created_at, expires_at, revoked_at "
                                + "FROM user_session WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?",
                        (rs, rowNum) -> new SessionRecord(
                                rs.getString("id"),
                                rs.getString("user_id"),
                                rs.getString("token_hash"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("expires_at").toInstant(),
                                rs.getTimestamp("revoked_at") == null
                                        ? null
                                        : rs.getTimestamp("revoked_at").toInstant()),
                        tokenHash,
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    @Override
    public boolean revoke(String sessionId, Instant now) {
        return jdbcTemplate.update("UPDATE user_session SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(now), sessionId) > 0;
    }
}
