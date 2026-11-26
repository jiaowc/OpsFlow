package com.opsflow.common.exception;

/**
 * 无权限访问异常（HTTP 403）
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
