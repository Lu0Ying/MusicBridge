package com.musicbridge.security;

/**
 * 资源不存在，或资源属于其他用户时统一返回 404，避免泄露资源是否存在。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
