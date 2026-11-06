package com.musicbridge.web;

import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.manager.ProviderBudgetExhaustedException;
import com.musicbridge.security.AccessForbiddenException;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.security.UnauthenticatedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 Provider 归一化错误码映射为 HTTP 状态，避免业务层重复拼装响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiError> handleProviderException(ProviderException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case CONFIG_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case AUTH_INVALID -> HttpStatus.BAD_GATEWAY;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case QUOTA_EXCEEDED, RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TEMPORARY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNSUPPORTED_INPUT -> HttpStatus.BAD_REQUEST;
            case NO_MATCH -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.errorCode().name(), exception.getMessage(), exception.providerId()));
    }

    @ExceptionHandler(ProviderBudgetExhaustedException.class)
    public ResponseEntity<ApiError> handleBudgetExhausted(ProviderBudgetExhaustedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("BUDGET_EXHAUSTED", exception.getMessage(), null));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiError> handleUnauthenticated(UnauthenticatedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("UNAUTHENTICATED", exception.getMessage(), null));
    }

    @ExceptionHandler(AccessForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", exception.getMessage(), null));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", exception.getMessage(), null));
    }
}
