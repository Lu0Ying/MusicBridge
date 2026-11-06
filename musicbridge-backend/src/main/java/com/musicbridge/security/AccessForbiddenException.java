package com.musicbridge.security;

/**
 * 已认证但当前操作被拒绝（例如授权已撤销或过期）。
 */
public class AccessForbiddenException extends RuntimeException {

    public AccessForbiddenException(String message) {
        super(message);
    }
}
