package com.parknest.api.auth;

import com.parknest.api.auth.AuthDtos.AuthSession;
import com.parknest.api.auth.AuthDtos.GoogleLoginRequest;
import com.parknest.api.auth.AuthDtos.OtpSendRequest;
import com.parknest.api.auth.AuthDtos.OtpSendResult;
import com.parknest.api.auth.AuthDtos.OtpVerifyRequest;
import com.parknest.api.auth.AuthDtos.RefreshRequest;
import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** POST /auth/otp/send */
    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<OtpSendResult>> sendOtp(@Valid @RequestBody OtpSendRequest req) {
        return Responses.ok("OTP sent", auth.sendLoginOtp(req.phone()));
    }

    /** POST /auth/otp/verify */
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<AuthSession>> verifyOtp(@Valid @RequestBody OtpVerifyRequest req, HttpServletRequest http) {
        return Responses.ok("Logged in", auth.verifyLoginOtp(req, http.getHeader("User-Agent"), http.getRemoteAddr()));
    }

    /** POST /auth/google */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthSession>> google(@Valid @RequestBody GoogleLoginRequest req, HttpServletRequest http) {
        return Responses.ok("Logged in with Google", auth.loginWithGoogle(req.idToken(), http.getHeader("User-Agent"), http.getRemoteAddr()));
    }

    /** POST /auth/refresh: new tokens for a refresh token (which then stops working). */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthSession>> refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return Responses.ok("Session refreshed", auth.refresh(req.refreshToken(), http.getHeader("User-Agent"), http.getRemoteAddr()));
    }

    /** POST /auth/logout */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return Responses.ok("Logged out", Map.of("loggedOut", true));
    }
}
