package com.parknest.api.common;

/** Money as the app shows it: whole rupees. The database keeps paise. */
public record Money(String currency, long amount) {

    public static Money ofPaise(long paise) {
        return new Money("INR", paise / 100);
    }
}
