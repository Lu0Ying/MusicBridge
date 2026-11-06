package com.musicbridge.web;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message, String providerId) {
}
