package com.parknest.api.listings;

import com.parknest.api.common.Geo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Public spot shapes (frontend listing.model.ts, search.model.ts, quote.model.ts). Money is whole rupees. */
public final class ListingDtos {

    private ListingDtos() {}

    public record Address(String line1, String area, String city, String state, String pincode, Geo.Point geo) {}

    public record Pricing(String currency, long perDay, long perHour) {}

    public record AccessHours(boolean is24x7, String opensAt, String closesAt) {}

    public record HostSummary(String id, String name, boolean isVerified) {}

    /** ListingSummary: search cards. distanceKm is from the searched place. */
    public record ListingSummary(String id, String title, Address address, Double distanceKm, String spotType, int bays,
                                 List<String> vehicleTypes, Pricing pricing, String bookingMode, List<String> security,
                                 AccessHours accessHours, String thumbnailUrl, HostSummary host) {}

    public record Photo(String id, String url, String caption) {}

    public record DetailAccessHours(boolean is24x7, String days, String opensAt, String closesAt) {}

    public record CancellationPolicy(String type, int hoursBeforeStart, String text) {}

    public record DetailHost(String id, String name, boolean isVerified, Integer respondsInMinutes, String memberSince) {}

    /** ListingDetail: the spot page. Entry instructions are never here (only on a paid booking). */
    public record ListingDetail(String id, String title, Address address, Double distanceKm, String spotType, int bays,
                                List<String> vehicleTypes, Pricing pricing, String bookingMode, List<String> security,
                                String thumbnailUrl, String bayLabel, String description, List<Photo> photos,
                                DetailAccessHours accessHours, CancellationPolicy cancellationPolicy, DetailHost host,
                                List<String> rules) {}

    // ---------- Search ----------

    public record SearchLocation(String label, Geo.Point geo) {}

    public record SearchFilters(boolean instantBookOnly, boolean coveredOnly, boolean cctv, Long maxPricePerDay) {}

    public record SearchRequest(
            @NotNull(message = "Choose where to park") SearchLocation location,
            Geo.Bounds area,
            String vehicleType,
            String spotType,
            @NotBlank(message = "Choose a start time") String startAt,
            @NotBlank(message = "Choose an end time") String endAt,
            SearchFilters filters,
            String sort,
            @Min(1) Integer page,
            @Min(1) @Max(50) Integer pageSize) {}

    // ---------- Quote ----------

    public record QuoteRequest(@NotBlank String startAt, @NotBlank String endAt, @NotBlank(message = "Choose your vehicle") String vehicleType) {}

    public record QuoteLine(String code, String label, long amount) {}

    public record Quote(String quoteId, String listingId, String currency, int durationDays, long durationHours, String bookingMode,
                        List<QuoteLine> lineItems, long total, String validUntil) {}
}
