package com.parknest.api.bookings;

import com.parknest.api.common.Money;
import com.parknest.api.listings.ListingDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Booking shapes (frontend booking.model.ts and host.model.ts). */
public final class BookingDtos {

    private BookingDtos() {}

    public record Vehicle(String registrationNumber, @NotBlank(message = "Choose your vehicle") String type) {}

    public record CreateRequest(@NotBlank String listingId, String quoteId, @NotNull @Valid Vehicle vehicle,
                                @NotBlank String startAt, @NotBlank String endAt, String paymentMethod) {}

    public record UpdateRequest(@NotNull @Valid Vehicle vehicle) {}

    public record PaymentOrder(String provider, String orderId, String clientToken, List<String> methods) {}

    /** BookingHold */
    public record Hold(String bookingId, String bookingRef, String status, String bookingMode, String holdExpiresAt, Money amount, PaymentOrder payment) {}

    public record PaymentVerifyRequest(@NotBlank String bookingId, @NotBlank String orderId, String paymentId, String signature, String paymentMethod) {}

    public record PaymentVerifyResult(String bookingId, String bookingRef, String status, Money paidAmount) {}

    public record TimelineStep(String code, String label, String status, String at) {}

    public record Cancellation(String cancelledAt, Money refund, String refundStatus) {}

    public record DetailListing(String id, String title, String bayLabel, ListingDtos.Address address) {}

    /** BookingDetail */
    public record Detail(String id, String bookingRef, String status, String bookingMode, DetailListing listing, Vehicle vehicle,
                         String startAt, String endAt, int durationDays, Money amount, String paymentMethod, String entryInstructions,
                         String checkInOpensAt, List<TimelineStep> timeline, Settlement settlement, String freeCancellationUntil,
                         Cancellation cancellation) {}

    public record Settlement(String status) {}

    public record SummaryListing(String id, String title, String area, String thumbnailUrl) {}

    /** BookingSummary (My bookings) */
    public record Summary(String id, String bookingRef, String status, String bookingMode, SummaryListing listing, Vehicle vehicle,
                          String startAt, String endAt, int durationDays, Money amount, String holdExpiresAt, String checkedInAt,
                          String checkedOutAt, Cancellation cancellation) {}

    public record StatusChange(String bookingId, String status, String checkedInAt, String checkedOutAt) {}

    /** CancellationPreview */
    public record CancellationPreview(String bookingId, boolean canCancel, String reason, String freeCancellationUntil, boolean isFree,
                                      Money paid, Money refund, Money nonRefundable, String refundTo, String refundEta, String policy) {}

    public record CancelRequest(String reason, String note) {}

    public record CancelResult(String bookingId, String status, String cancelledAt, Money refund, String refundStatus, String refundEta) {}

    // ---------- Host ----------

    public record Guest(String name) {}

    public record HostVehicle(String type, String registrationNumber) {}

    /** BookingRequest (dashboard) */
    public record BookingRequest(String id, String status, Guest guest, HostVehicle vehicle, String listingId, String startAt, String endAt,
                                 int durationDays, Money amount, List<String> overlappingRequestIds) {}

    public record DecisionRequest(String decision, String reason) {}

    public record DecisionResult(String requestId, String status, String bookingId, List<String> autoDeclinedRequestIds) {}

    public record DashboardListing(String id, String title, String bayLabel, long pricePerDay, String status, int bookingsThisMonth) {}

    public record EarningsThisMonth(String currency, long amount, int changePercent) {}

    public record DashboardSummary(EarningsThisMonth earningsThisMonth, int bookingsThisMonth, int upcomingBookings, int occupancyPercent,
                                   Money pendingPayout, String nextPayoutDate) {}

    public record Dashboard(DashboardSummary summary, List<BookingRequest> bookingRequests, List<DashboardListing> listings) {}

    public record HostListingRef(String id, String title) {}

    /** HostBooking */
    public record HostBooking(String id, String bookingRef, String status, Guest driver, HostVehicle vehicle, HostListingRef listing,
                              String startAt, String endAt, int durationDays, Money hostEarnings, String checkedInAt, String checkedOutAt) {}

    public record PayoutView(String id, String status, Money amount, int bookingCount, String initiatedAt, String paidAt, String destination,
                             String failureReason) {}

    public record EarningsSummary(Money thisMonth, Money pendingPayout, Money paidThisYear, String nextPayoutDate) {}

    public record MonthAmount(String month, long amount) {}

    public record Earnings(EarningsSummary summary, List<MonthAmount> monthly, List<PayoutView> payouts) {}
}
