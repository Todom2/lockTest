package org.example.locktest;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final StatusCode statusCode;

    public BusinessException(StatusCode statusCode) {
        super(statusCode.getMessage());
        this.statusCode = statusCode;
    }

    public BusinessException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}