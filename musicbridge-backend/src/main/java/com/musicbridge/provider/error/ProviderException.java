package com.musicbridge.provider.error;

import java.time.Duration;

/**
 * Provider 调用失败的统一异常。
 * <p>adapter 负责把 HTTP 状态码与返回体错误码归一为 {@link ProviderErrorCode}，
 * ProviderManager 依据错误码决定重试、切换、熔断与停用。</p>
 */
public class ProviderException extends RuntimeException {

    private final String providerId;
    private final ProviderErrorCode errorCode;
    private final PermissionScope permissionScope;
    private final String providerErrorCode;
    private final Integer httpStatus;
    private final Duration retryAfter;

    public ProviderException(String providerId,
                             ProviderErrorCode errorCode,
                             String message,
                             PermissionScope permissionScope,
                             String providerErrorCode,
                             Integer httpStatus,
                             Duration retryAfter,
                             Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.errorCode = errorCode;
        this.permissionScope = permissionScope;
        this.providerErrorCode = providerErrorCode;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public static ProviderException of(String providerId, ProviderErrorCode code, String message) {
        return new ProviderException(providerId, code, message, null, null, null, null, null);
    }

    public static ProviderException of(String providerId, ProviderErrorCode code, String message, Throwable cause) {
        return new ProviderException(providerId, code, message, null, null, null, null, cause);
    }

    public static ProviderException unsupportedInput(String providerId, String message) {
        return of(providerId, ProviderErrorCode.UNSUPPORTED_INPUT, message);
    }

    public static ProviderException noMatch(String providerId, String message) {
        return of(providerId, ProviderErrorCode.NO_MATCH, message);
    }

    public static ProviderException rateLimited(String providerId, String message, Duration retryAfter) {
        return new ProviderException(providerId, ProviderErrorCode.RATE_LIMITED, message,
                null, null, 429, retryAfter, null);
    }

    public static ProviderException temporary(String providerId, String message, Throwable cause) {
        return of(providerId, ProviderErrorCode.TEMPORARY_UNAVAILABLE, message, cause);
    }

    public String providerId() {
        return providerId;
    }

    public ProviderErrorCode errorCode() {
        return errorCode;
    }

    public PermissionScope permissionScope() {
        return permissionScope;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** 账号级 403 或未知范围的 403 视为不可切换 */
    public boolean isAccountLevelPermissionDenied() {
        return errorCode == ProviderErrorCode.PERMISSION_DENIED
                && permissionScope != PermissionScope.CAPABILITY;
    }
}
