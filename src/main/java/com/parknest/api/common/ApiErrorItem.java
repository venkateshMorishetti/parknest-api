package com.parknest.api.common;

/** One error: a stable code (e.g. OTP_INVALID), the request field it belongs to (or null), and a message for people. */
public record ApiErrorItem(String code, String field, String message) {}
