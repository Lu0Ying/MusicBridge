package com.musicbridge.web;

/**
 * 请求实体过大（413）。
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
