package com.parknest.api.users;

import com.parknest.api.auth.AuthDtos.OtpSendResult;
import com.parknest.api.auth.AuthService;
import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import com.parknest.api.users.UserDtos.AppUser;
import com.parknest.api.users.UserDtos.SavedVehicle;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me")
public class UserController {

    private final UserService users;
    private final AuthService auth;

    public UserController(UserService users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    /** GET /users/me */
    @GetMapping
    public ResponseEntity<ApiResponse<AppUser>> me(@AuthenticationPrincipal Jwt jwt) {
        return Responses.ok("Profile fetched", users.me(jwt.getSubject()));
    }

    /** PUT /users/me */
    @PutMapping
    public ResponseEntity<ApiResponse<AppUser>> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserDtos.UpdateProfileRequest req) {
        return Responses.ok("Profile updated", users.updateProfile(jwt.getSubject(), req));
    }

    /** POST /users/me/phone/otp: sends a code to the number being added (Google sign-ins). */
    @PostMapping("/phone/otp")
    public ResponseEntity<ApiResponse<OtpSendResult>> phoneOtp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserDtos.PhoneOtpRequest req) {
        return Responses.ok("OTP sent", auth.sendPhoneLinkOtp(jwt.getSubject(), req.phone()));
    }

    /** POST /users/me/phone/verify */
    @PostMapping("/phone/verify")
    public ResponseEntity<ApiResponse<AppUser>> phoneVerify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserDtos.PhoneVerifyRequest req) {
        return Responses.ok("Mobile number verified", auth.verifyPhoneLink(jwt.getSubject(), req.otpRequestId(), req.phone(), req.otp()));
    }

    /** POST /users/me/vehicles */
    @PostMapping("/vehicles")
    public ResponseEntity<ApiResponse<SavedVehicle>> addVehicle(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserDtos.AddVehicleRequest req) {
        return Responses.created("Vehicle added", users.addVehicle(jwt.getSubject(), req));
    }

    /** PATCH /users/me/vehicles/:id */
    @PatchMapping("/vehicles/{id}")
    public ResponseEntity<ApiResponse<SavedVehicle>> updateVehicle(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                   @Valid @RequestBody UserDtos.UpdateVehicleRequest req) {
        return Responses.ok("Vehicle updated", users.updateVehicle(jwt.getSubject(), id, req));
    }

    /** DELETE /users/me/vehicles/:id */
    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeVehicle(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        users.removeVehicle(jwt.getSubject(), id);
        return Responses.ok("Vehicle removed", Map.of("id", id, "deleted", true));
    }
}
