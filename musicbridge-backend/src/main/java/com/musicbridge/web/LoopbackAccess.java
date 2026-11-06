package com.musicbridge.web;

/**
 * 回环来源判定：健康检查类端点只允许本机访问。
 */
public final class LoopbackAccess {

    private LoopbackAccess() {
    }

    public static boolean isLoopback(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }
}
