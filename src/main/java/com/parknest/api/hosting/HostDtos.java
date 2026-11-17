package com.parknest.api.hosting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Hosting request and response bodies (frontend host.model.ts). Money is whole rupees. */
public final class HostDtos {

    private HostDtos() {}

    /** PUT /host/payout-account, and "payout" inside POST /host/onboarding. */
    public record PayoutRequest(String method, String upiId, String accountHolderName, String accountNumber, String ifsc) {}

    /** PayoutAccountView */
    public record PayoutAccountView(String method, String display, String accountHolderName, String status) {}

    public record OnboardingRequest(@NotNull(message = "Add where to send your earnings") @Valid PayoutRequest payout, Boolean acceptedHostTerms) {}

    /** HostOnboarding */
    public record Onboarding(String status, boolean canHost, String phone, boolean phoneVerified, PayoutAccountView payoutAccount, String activatedAt) {}

    public record Channels(boolean sms, boolean email) {}

    public record NotificationSettings(@NotNull @Valid Channels newRequests, @NotNull @Valid Channels bookingUpdates,
                                       @NotNull @Valid Channels payouts, @NotNull @Valid Channels tips) {}

    public record SettingsProfile(String name, String phone, String email, String verifiedAt) {}

    public record HostSettings(SettingsProfile profile, PayoutAccountView payoutAccount, NotificationSettings notifications) {}

    // ---------- Listings ----------

    public record Geo(Double lat, Double lng) {}

    public record ListingAddress(String line1, String area, String city, String state, String pincode, Geo geo) {}

    public record Pricing(String currency, Long perDay, Long perHour) {}

    public record AccessHours(Boolean is24x7, String opensAt, String closesAt) {}

    /** POST /host/listings and PUT /host/listings/{id} */
    public record ListingRequest(String title, ListingAddress address, String bayLabel, String entryInstructions, String spotType,
                                 Integer bays, List<String> vehicleTypes, List<String> security, Pricing pricing,
                                 AccessHours accessHours, String bookingMode, List<String> photoFileIds) {}

    public record CreatedListing(String id, String status, String submittedAt, String reviewEstimate) {}

    public record UpdatedListing(String id, String status, String updatedAt, String reviewEstimate) {}

    public record StatusRequest(String status) {}

    public record StatusResult(String id, String status, String updatedAt) {}

    /** ManagedListing (My listings) */
    public record ManagedListing(String id, String title, ListingAddress address, String bayLabel, String spotType, int bays,
                                 List<String> vehicleTypes, Pricing pricing, String bookingMode, AccessHours accessHours,
                                 String status, int bookingsThisMonth, int photoCount, String thumbnailUrl, String updatedAt,
                                 String reviewNote) {}

    public record EditablePhoto(String fileId, String url) {}

    /** EditableListing (edit form) */
    public record EditableListing(String id, String title, ListingAddress address, String bayLabel, String entryInstructions,
                                  String spotType, int bays, List<String> vehicleTypes, List<String> security, Pricing pricing,
                                  AccessHours accessHours, String bookingMode, String status, List<EditablePhoto> photos,
                                  String updatedAt) {}
}
