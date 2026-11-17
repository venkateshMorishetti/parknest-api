package com.parknest.api.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every exception into the standard error envelope. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> api(ApiException e) {
        return error(e.status(), e.title(), e.errors());
    }

    /** Bean Validation on request bodies: one error per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        List<ApiErrorItem> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiErrorItem(codeFor(f.getCode()), f.getField(), f.getDefaultMessage()))
                .toList();
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Bad request", List.of(new ApiErrorItem("INVALID_JSON", null, "The request body is not valid JSON")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Void>> missingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
                List.of(new ApiErrorItem("REQUIRED", e.getParameterName(), e.getParameterName() + " is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
                List.of(new ApiErrorItem("INVALID_FIELD", e.getName(), e.getName() + " has the wrong format")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> noRoute(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "Not found", List.of(new ApiErrorItem("NOT_FOUND", null, "No such endpoint")));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> method(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                List.of(new ApiErrorItem("METHOD_NOT_ALLOWED", null, e.getMethod() + " is not supported here")));
    }

    /** Two requests racing for the same unique value (phone, email, booking ref...). */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<ApiResponse<Void>> duplicate(org.springframework.dao.DuplicateKeyException e) {
        log.warn("Duplicate key: {}", e.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "Conflict", List.of(new ApiErrorItem("CONFLICT", null, "This was changed at the same time by another request. Try again.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong",
                List.of(new ApiErrorItem("INTERNAL_ERROR", null, "Something went wrong on our side. Try again.")));
    }

    static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String message, List<ApiErrorItem> errors) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message, errors, Responses.meta(java.util.Map.of())));
    }

    private static String codeFor(String constraint) {
        if (constraint == null) return "INVALID_FIELD";
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            default -> "INVALID_FIELD";
        };
    }
}
