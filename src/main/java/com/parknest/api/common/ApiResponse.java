package com.parknest.api.common;

import java.util.List;
import java.util.Map;

/**
 * The envelope every endpoint returns, success or failure (the same shape as the frontend's mock-data):
 * {@code { success, status, message, data, errors, meta }}.
 */
public record ApiResponse<T>(
        boolean success,
        int status,
        String message,
        T data,
        List<ApiErrorItem> errors,
        Map<String, Object> meta) {

    public static <T> ApiResponse<T> ok(int status, String message, T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, status, message, data, null, meta);
    }

    public static ApiResponse<Void> fail(int status, String message, List<ApiErrorItem> errors, Map<String, Object> meta) {
        return new ApiResponse<>(false, status, message, null, errors, meta);
    }
}
