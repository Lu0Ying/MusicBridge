package com.musicbridge.security;

import java.time.Instant;

/**
 * 认证过滤器解析出的服务端身份上下文。
 *
 * <p>业务代码只从这里读取 userId，不接受客户端在请求体或查询参数里传入的 userId。
 */
public record AuthContext(String userId, String sessionId, Instant expiresAt) {

    /** 请求属性名，供控制器通过 {@code @RequestAttribute} 注入 */
    public static final String REQUEST_ATTRIBUTE = "musicbridge.authContext";
}
