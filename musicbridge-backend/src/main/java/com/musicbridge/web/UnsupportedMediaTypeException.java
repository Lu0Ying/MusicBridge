package com.musicbridge.web;

/**
 * 不支持的媒体类型或内容与声明格式不一致（415）。
 */
public class UnsupportedMediaTypeException extends RuntimeException {

    public UnsupportedMediaTypeException(String message) {
        super(message);
    }
}
