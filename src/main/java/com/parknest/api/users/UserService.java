package com.parknest.api.users;

import com.parknest.api.auth.AuthIdentity;
import com.parknest.api.auth.AuthIdentityRepository;
import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.hosting.HostProfile;
import com.parknest.api.hosting.HostProfileRepository;
import com.parknest.api.users.UserDtos.AppUser;
import com.parknest.api.users.UserDtos.SavedVehicle;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's profile and saved vehicles. */
@Service
public class UserService {

    /** "KA01AB1234", "MH12A1", "DL3CAB1234" (spaces removed, upper case). */
    private static final Pattern REGISTRATION = Pattern.compile("^[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{1,4}$");
    private static final Map<String, String> VEHICLE_LABELS = Map.of(
            "HATCHBACK", "Car · Hatchback", "SEDAN", "Car · Sedan", "SUV", "Car · SUV", "BIKE", "Bike");
    static final int MAX_VEHICLES = 5;

    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final AuthIdentityRepository identities;
    private final HostProfileRepository hostProfiles;

    public UserService(UserRepository users, VehicleRepository vehicles, AuthIdentityRepository identities, HostProfileRepository hostProfiles) {
        this.users = users;
        this.vehicles = vehicles;
        this.identities = identities;
        this.hostProfiles = hostProfiles;
    }

    @Transactional(readOnly = true)
    public AppUser me(String userId) {
        return toAppUser(find(userId));
    }

    public User find(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    public AppUser toAppUser(User u) {
        List<String> providers = identities.findByUserIdOrderByProviderDesc(u.getId()).stream().map(AuthIdentity::getProvider).toList();
        String hostStatus = hostProfiles.findById(u.getId()).map(HostProfile::getStatus).orElse("NOT_STARTED");
        return new AppUser(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getAvatarUrl(), providers, u.isPhoneVerified(), u.isEmailVerified(),
                new UserDtos.HostProfile(hostStatus), activeVehicles(u.getId()).stream().map(UserService::toDto).toList(), List.copyOf(u.getRoles()));
    }

    /** PUT /users/me: a changed email needs verifying again. */
    @Transactional
    public AppUser updateProfile(String userId, UserDtos.UpdateProfileRequest req) {
        User user = find(userId);
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        if (email != null) {
            users.findByEmailIgnoreCase(email).filter(u -> !u.getId().equals(userId)).ifPresent(u -> {
                throw ApiException.conflict("EMAIL_IN_USE", "email", "This email is used by another ParkNest account");
            });
        }
        boolean sameEmail = email != null && email.equalsIgnoreCase(user.getEmail());
        user.setName(req.name().trim());
        user.setEmail(email);
        if (!sameEmail) user.setEmailVerifiedAt(null);
        return toAppUser(users.saveAndFlush(user));
    }

    @Transactional
    public SavedVehicle addVehicle(String userId, UserDtos.AddVehicleRequest req) {
        String reg = normalise(req.registrationNumber());
        if (!REGISTRATION.matcher(reg).matches()) {
            throw ApiException.invalid("INVALID_REGISTRATION", "registrationNumber", "Enter a valid vehicle number, e.g. KA 01 AB 1234");
        }
        List<Vehicle> saved = activeVehicles(userId);
        if (saved.stream().anyMatch(v -> v.getRegistrationNumber().equals(reg))) {
            throw ApiException.conflict("DUPLICATE_VEHICLE", "registrationNumber", "This vehicle number is already in your list");
        }
        if (saved.size() >= MAX_VEHICLES) {
            throw ApiException.conflict("VEHICLE_LIMIT", null, "You can save up to 5 vehicles. Remove one first.");
        }
        boolean makeDefault = Boolean.TRUE.equals(req.isDefault()) || saved.isEmpty();
        if (makeDefault) vehicles.clearDefault(userId);
        Vehicle v = new Vehicle();
        v.setId(Ids.newId("veh"));
        v.setUserId(userId);
        v.setRegistrationNumber(reg);
        v.setType(req.type());
        v.setNickname(blankToNull(req.nickname()));
        v.setDefault(makeDefault);
        return toDto(vehicles.saveAndFlush(v));
    }

    @Transactional
    public SavedVehicle updateVehicle(String userId, String vehicleId, UserDtos.UpdateVehicleRequest req) {
        findVehicle(userId, vehicleId);
        if (Boolean.TRUE.equals(req.isDefault())) vehicles.clearDefault(userId);
        Vehicle v = findVehicle(userId, vehicleId);  // reloaded after the bulk update
        if (req.nickname() != null) v.setNickname(blankToNull(req.nickname()));
        if (Boolean.TRUE.equals(req.isDefault())) v.setDefault(true);
        return toDto(vehicles.saveAndFlush(v));
    }

    /** Removing the default vehicle makes the oldest remaining one the default. */
    @Transactional
    public void removeVehicle(String userId, String vehicleId) {
        Vehicle target = findVehicle(userId, vehicleId);
        boolean wasDefault = target.isDefault();
        target.setDeletedAt(java.time.OffsetDateTime.now());
        target.setDefault(false);
        vehicles.saveAndFlush(target);
        if (wasDefault) activeVehicles(userId).stream().findFirst().ifPresent(v -> v.setDefault(true));
    }

    private List<Vehicle> activeVehicles(String userId) {
        return vehicles.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(userId);
    }

    private Vehicle findVehicle(String userId, String vehicleId) {
        return vehicles.findByIdAndUserIdAndDeletedAtIsNull(vehicleId, userId).orElseThrow(() -> ApiException.notFound("Vehicle"));
    }

    static SavedVehicle toDto(Vehicle v) {
        return new SavedVehicle(v.getId(), v.getRegistrationNumber(), v.getType(), VEHICLE_LABELS.get(v.getType()), v.getNickname(), v.isDefault());
    }

    static String normalise(String registration) {
        return registration == null ? "" : registration.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
