package com.parknest.api.users;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.users.UserDtos.AppUser;
import com.parknest.api.users.UserDtos.SavedVehicle;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient db;

    public UserService(UserRepository users, VehicleRepository vehicles, JdbcClient db) {
        this.users = users;
        this.vehicles = vehicles;
        this.db = db;
    }

    public AppUser me(String userId) {
        return toAppUser(users.findById(userId).orElseThrow(() -> ApiException.notFound("User")));
    }

    public AppUser toAppUser(User u) {
        List<String> providers = db.sql("SELECT provider FROM auth_identities WHERE user_id = :id ORDER BY provider DESC")
                .param("id", u.id()).query(String.class).list();
        return new AppUser(u.id(), u.name(), u.email(), u.phone(), u.avatarUrl(), providers, u.phoneVerified(), u.emailVerifiedAt() != null,
                new UserDtos.HostProfile(u.hostStatus()), vehicles.listForUser(u.id()).stream().map(UserService::toDto).toList(), u.roles());
    }

    /** PUT /users/me: a changed email needs verifying again. */
    @Transactional
    public AppUser updateProfile(String userId, UserDtos.UpdateProfileRequest req) {
        User current = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        if (email != null) {
            users.findByEmail(email).filter(u -> !u.id().equals(userId)).ifPresent(u -> {
                throw ApiException.conflict("EMAIL_IN_USE", "email", "This email is used by another ParkNest account");
            });
        }
        boolean sameEmail = email != null && email.equalsIgnoreCase(current.email());
        users.updateProfile(userId, req.name().trim(), email, sameEmail);
        return me(userId);
    }

    @Transactional
    public SavedVehicle addVehicle(String userId, UserDtos.AddVehicleRequest req) {
        String reg = normalise(req.registrationNumber());
        if (!REGISTRATION.matcher(reg).matches()) {
            throw ApiException.invalid("INVALID_REGISTRATION", "registrationNumber", "Enter a valid vehicle number, e.g. KA 01 AB 1234");
        }
        List<VehicleRepository.Vehicle> saved = vehicles.listForUser(userId);
        if (saved.stream().anyMatch(v -> v.registrationNumber().equals(reg))) {
            throw ApiException.conflict("DUPLICATE_VEHICLE", "registrationNumber", "This vehicle number is already in your list");
        }
        if (saved.size() >= MAX_VEHICLES) {
            throw ApiException.conflict("VEHICLE_LIMIT", null, "You can save up to 5 vehicles. Remove one first.");
        }
        boolean makeDefault = Boolean.TRUE.equals(req.isDefault()) || saved.isEmpty();
        if (makeDefault) vehicles.clearDefault(userId);
        String id = Ids.newId("veh");
        vehicles.insert(id, userId, reg, req.type(), blankToNull(req.nickname()), makeDefault);
        return toDto(vehicles.find(userId, id).orElseThrow());
    }

    @Transactional
    public SavedVehicle updateVehicle(String userId, String vehicleId, UserDtos.UpdateVehicleRequest req) {
        vehicles.find(userId, vehicleId).orElseThrow(() -> ApiException.notFound("Vehicle"));
        if (req.nickname() != null) vehicles.setNickname(userId, vehicleId, blankToNull(req.nickname()));
        if (Boolean.TRUE.equals(req.isDefault())) {
            vehicles.clearDefault(userId);
            vehicles.setDefault(userId, vehicleId);
        }
        return toDto(vehicles.find(userId, vehicleId).orElseThrow());
    }

    /** Removing the default vehicle makes the oldest remaining one the default. */
    @Transactional
    public void removeVehicle(String userId, String vehicleId) {
        VehicleRepository.Vehicle target = vehicles.find(userId, vehicleId).orElseThrow(() -> ApiException.notFound("Vehicle"));
        vehicles.softDelete(userId, vehicleId);
        if (target.isDefault()) {
            vehicles.listForUser(userId).stream().findFirst().ifPresent(v -> vehicles.setDefault(userId, v.id()));
        }
    }

    static SavedVehicle toDto(VehicleRepository.Vehicle v) {
        return new SavedVehicle(v.id(), v.registrationNumber(), v.type(), VEHICLE_LABELS.get(v.type()), v.nickname(), v.isDefault());
    }

    static String normalise(String registration) {
        return registration == null ? "" : registration.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
