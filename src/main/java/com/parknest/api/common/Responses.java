package com.parknest.api.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Builds envelopes with the standard meta (requestId, timestamp, apiVersion). */
public final class Responses {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String API_VERSION = "v1";

    private Responses() {}

    public static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return of(HttpStatus.OK, message, data, Map.of());
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data, Map<String, Object> extraMeta) {
        return of(HttpStatus.OK, message, data, extraMeta);
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(String message, T data) {
        return of(HttpStatus.CREATED, message, data, Map.of());
    }

    public static <T> ResponseEntity<ApiResponse<T>> of(HttpStatus status, String message, T data, Map<String, Object> extraMeta) {
        return ResponseEntity.status(status).body(ApiResponse.ok(status.value(), message, data, meta(extraMeta)));
    }

    public static Map<String, Object> meta(Map<String, Object> extra) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", RequestIds.current());
        meta.put("timestamp", OffsetDateTime.now(IST).truncatedTo(ChronoUnit.SECONDS).toString());
        meta.put("apiVersion", API_VERSION);
        meta.putAll(extra);
        return meta;
    }
}
