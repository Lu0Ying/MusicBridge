package com.musicbridge.security;

import java.time.Instant;

/**
 * 登录成功后返回给客户端的会话令牌；令牌原文只出现这一次。
 */
public record SessionToken(String token, Instant expiresAt, String userId) {
}
