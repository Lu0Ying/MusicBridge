package com.musicbridge.security;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 本机最小会话服务：账号密码来自配置，令牌随机生成且只保存哈希。
 */
@Service
public class SessionService {

    private static final int TOKEN_BYTES = 32;

    private final SessionRepository repository;
    private final SecurityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository repository, SecurityProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 校验本地账号并签发会话；密码未配置时登录不可用。
     */
    public SessionToken login(String username, String password) {
        String configuredUsername = properties.getLocalUsername();
        String configuredPassword = properties.getLocalPassword();
        boolean passwordConfigured = configuredPassword != null && !configuredPassword.isBlank();
        boolean usernameMatches = configuredUsername != null && configuredUsername.equals(username);
        if (!passwordConfigured || !usernameMatches || password == null
                || !MessageDigest.isEqual(configuredPassword.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthenticatedException("用户名或密码不正确");
        }
        return issueForUser(properties.getLocalUserId());
    }

    /**
     * 仅供内部与测试调用；生产不暴露给客户端。
     */
    SessionToken issueForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("本地用户 ID 未配置");
        }
        Duration ttl = properties.getSessionTtl() == null ? Duration.ofDays(7) : properties.getSessionTtl();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        String token = newToken();
        repository.save(new SessionRecord(UUID.randomUUID().toString(), userId, hash(token), now, expiresAt, null));
        return new SessionToken(token, expiresAt, userId);
    }

    public Optional<AuthContext> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findActive(hash(token), clock.instant())
                .map(session -> new AuthContext(session.userId(), session.id(), session.expiresAt()));
    }

    public void revokeSession(String sessionId) {
        repository.revoke(sessionId, clock.instant());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
