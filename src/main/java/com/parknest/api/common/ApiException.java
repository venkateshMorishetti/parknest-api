package com.parknest.api.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Thrown by services for any expected failure. The handler turns it into the error envelope.
 * Example: {@code throw ApiException.conflict("PHONE_IN_USE", "phone", "This number belongs to another account")}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final List<ApiErrorItem> errors;

    public ApiException(HttpStatus status, String title, List<ApiErrorItem> errors) {
        super(errors.isEmpty() ? title : errors.getFirst().message());
        this.status = status;
        this.title = title;
        this.errors = List.copyOf(errors);
    }

    public ApiException(HttpStatus status, String title, String code, String field, String message) {
        this(status, title, List.of(new ApiErrorItem(code, field, message)));
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "INVALID_FIELD", field, message);
    }

    public static ApiException invalid(String code, String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", code, field, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found", "NOT_FOUND", null, "This " + what.toLowerCase() + " does not exist");
    }

    public static ApiException conflict(String code, String field, String message) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", code, field, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "Not allowed", code, null, message);
    }

    public static ApiException badRequest(String code, String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", code, field, message);
    }

    public static ApiException tooMany(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "RATE_LIMITED", null, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public List<ApiErrorItem> errors() {
        return errors;
    }
}
