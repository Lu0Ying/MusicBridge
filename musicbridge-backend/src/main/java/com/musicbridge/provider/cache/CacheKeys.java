package com.musicbridge.provider.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 缓存键工具：指纹/音频哈希只保留摘要前缀，不进入普通日志与缓存原文。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String sha256Prefix(String raw, int hexLength) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, Math.min(hexLength, hash.length * 2));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
