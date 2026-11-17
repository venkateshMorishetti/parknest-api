package com.parknest.api.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies for /users/me (same shapes as the frontend's auth.model.ts). */
public final class UserDtos {

    private UserDtos() {}

    /** AppUser */
    public record AppUser(
            String id,
            String name,
            String email,
            String phone,
            String avatarUrl,
            List<String> authProviders,
            boolean isPhoneVerified,
            boolean isEmailVerified,
            HostProfile hostProfile,
            List<SavedVehicle> vehicles,
            List<String> roles) {}

    public record HostProfile(String status) {}

    /** SavedVehicle */
    public record SavedVehicle(String id, String registrationNumber, String type, String label, String nickname, boolean isDefault) {}

    /** PUT /users/me */
    public record UpdateProfileRequest(
            @NotBlank(message = "Enter your name (2 to 60 characters)") @Size(min = 2, max = 60, message = "Enter your name (2 to 60 characters)") String name,
            @Email(message = "Enter a valid email address") @Size(max = 254) String email) {}

    /** POST /users/me/vehicles */
    public record AddVehicleRequest(
            @NotBlank(message = "Enter the vehicle number") String registrationNumber,
            @NotBlank(message = "Choose the vehicle type") @Pattern(regexp = "HATCHBACK|SEDAN|SUV|BIKE", message = "Choose the vehicle type") String type,
            @Size(max = 30, message = "Keep the nickname under 30 characters") String nickname,
            Boolean isDefault) {}

    /** PATCH /users/me/vehicles/:id (only the fields sent are changed) */
    public record UpdateVehicleRequest(
            @Size(max = 30, message = "Keep the nickname under 30 characters") String nickname,
            Boolean isDefault) {}

    /** POST /users/me/phone/otp */
    public record PhoneOtpRequest(@NotBlank(message = "Enter your mobile number") String phone) {}

    /** POST /users/me/phone/verify */
    public record PhoneVerifyRequest(
            @NotBlank String otpRequestId,
            @NotBlank(message = "Enter your mobile number") String phone,
            @NotBlank(message = "Enter the 6-digit code") @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String otp) {}
}
