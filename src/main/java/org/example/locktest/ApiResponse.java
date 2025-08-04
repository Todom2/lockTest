package org.example.locktest;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ApiResponse<T> {
    private String code;
    private String message;

    private T data;
    private List<FieldError> errors;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime timestamp;

    //성공 응답(기본)
    public static <T> ApiResponse<T> of(T data) {
        return ApiResponse.<T>builder()
                .code(StatusCode.OK.getCode())
                .message(StatusCode.OK.getMessage())
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    //성공 응답(메시지 포함)
    public static <T> ApiResponse<T> of(T data, String message) {
        return ApiResponse.<T>builder()
                .code(StatusCode.OK.getCode())
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    //에러 응답(상태 코드 지정)
    public static <T> ApiResponse<T> of(StatusCode statusCode, T data) {
        return ApiResponse.<T>builder()
                .code(statusCode.getCode())
                .message(statusCode.getMessage())
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // 에러 응답 (기본)
    public static <T> ApiResponse<T> error(StatusCode statusCode) {
        return ApiResponse.<T>builder()
                .code(statusCode.getCode())
                .message(statusCode.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // 에러 응답 (메시지 포함)
    public static <T> ApiResponse<T> error(StatusCode statusCode, String message) {
        return ApiResponse.<T>builder()
                .code(statusCode.getCode())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // 에러 응답 (필드 에러 포함)
    public static <T> ApiResponse<T> error(StatusCode statusCode, List<FieldError> errors) {
        return ApiResponse.<T>builder()
                .code(statusCode.getCode())
                .message(statusCode.getMessage())
                .errors(errors)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Getter
    @Builder
    public static class FieldError {
        private String field;
        private String message;
        private String reason;
    }
}

