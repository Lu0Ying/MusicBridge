package com.musicbridge.web;

/**
 * 业务输入不合法（400）。与 Bean Validation 的格式校验区分：用于跨字段/状态相关的校验。
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
