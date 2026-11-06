package com.musicbridge.web;

import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.manager.ProviderBudgetExhaustedException;
import com.musicbridge.security.AccessForbiddenException;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.security.UnauthenticatedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.stream.Collectors;

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

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> handlePayloadTooLarge(PayloadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("PAYLOAD_TOO_LARGE", exception.getMessage(), null));
    }

    /** 容器层 multipart 限制触发时的兜底映射，与业务层实际字节校验保持同一状态码 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("PAYLOAD_TOO_LARGE", "文件超过 25 MB 上限", null));
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(UnsupportedMediaTypeException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", exception.getMessage(), null));
    }

    /** multipart 缺少 part 或结构无法解析 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "缺少必要的请求部分: " + exception.getRequestPartName(), null));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "multipart 请求无法解析", null));
    }

    /** Bean Validation：请求体字段校验失败，统一返回 400 + 字段级说明 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException exception) {
        String details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        return invalid(details);
    }

    /** Bean Validation：路径参数/查询参数校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(ConstraintViolationException exception) {
        String details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return invalid(details);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException exception) {
        return invalid(exception.getMessage());
    }

    /** 请求体不是合法 JSON、字段类型不匹配等解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "请求体无法解析", null));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ApiError> invalid(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", message, null));
    }
}
