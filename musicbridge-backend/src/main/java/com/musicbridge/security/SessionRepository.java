package com.musicbridge.security;

import java.time.Instant;
import java.util.Optional;

/**
 * 会话存储抽象，便于无数据库环境下做认证单测。
 */
public interface SessionRepository {

    void save(SessionRecord session);

    Optional<SessionRecord> findActive(String tokenHash, Instant now);

    boolean revoke(String sessionId, Instant now);
}
