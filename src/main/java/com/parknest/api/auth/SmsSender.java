package com.parknest.api.auth;

/** Sends OTP SMS. Production plugs in an SMS provider (MSG91, Twilio, Gupshup...). */
public interface SmsSender {

    void sendOtp(String phone, String code);
}
