package com.parknest.api.auth;

import com.parknest.api.users.UserDtos.AppUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request and response bodies for /auth (same shapes as the frontend's auth.model.ts). */
public final class AuthDtos {

    private AuthDtos() {}

    public record OtpSendRequest(@NotBlank(message = "Enter your mobile number") String phone) {}

    public record OtpSendResult(String otpRequestId, String maskedPhone, long expiresInSeconds, long resendAfterSeconds) {}

    public record OtpVerifyRequest(
            @NotBlank String otpRequestId,
            @NotBlank(message = "Enter your mobile number") String phone,
            @NotBlank(message = "Enter the 6-digit code") @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String otp) {}

    public record GoogleLoginRequest(@NotBlank String idToken) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record AuthSession(String tokenType, String accessToken, String refreshToken, long expiresInSeconds, boolean isNewUser, AppUser user) {}
}
