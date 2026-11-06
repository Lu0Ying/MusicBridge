package com.musicbridge.security;

/**
 * 缺少或提供了无效会话令牌。
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
