package com.parknest.api.admin;

import com.parknest.api.common.Geo;
import com.parknest.api.common.Money;
import java.util.List;

/** Admin panel shapes (frontend admin.model.ts). */
public final class AdminDtos {

    private AdminDtos() {}

    public record Person(String id, String name, String phone, String email) {}

    public record ListingHost(String id, String name, String phone, boolean isVerified) {}

    public record DecisionInfo(String decision, String reason, String decidedAt, String decidedBy) {}

    public record DecisionRequest(String decision, String reason) {}

    public record DecisionResult(String id, String status, String decidedAt) {}

    public record Address(String line1, String area, String city, String state, String pincode, Geo.Point geo) {}

    public record Pricing(String currency, long perDay, long perHour) {}

    public record AccessHours(boolean is24x7, String opensAt, String closesAt) {}

    public record Photo(String fileId, String url, String caption) {}

    public record Listing(String id, String title, ListingHost host, String status, String submittedAt, String reviewReason, Address address,
                          String bayLabel, String entryInstructions, String spotType, int bays, List<String> vehicleTypes, List<String> security,
                          Pricing pricing, AccessHours accessHours, String bookingMode, List<Photo> photos, DecisionInfo decision) {}

    public record User(String id, String name, String phone, String email, String joinedAt, String hostStatus, long bookingCount,
                       long listingCount, String status, String lastActiveAt) {}

    public record UserUpdate(String status, String reason) {}

    public record UserUpdateResult(String id, String status, String updatedAt) {}

    public record ListingRef(String id, String title) {}

    public record Booking(String id, String bookingRef, String status, String bookingMode, Person driver, Person host, ListingRef listing,
                          String startAt, String endAt, Money amount, Money hostEarnings, String createdAt) {}

    public record CancelRequest(String reason, String refund) {}

    public record CancelResult(String bookingId, String status, Money refund, String refundStatus) {}

    public record BookingRef(String id, String bookingRef) {}

    public record Refund(String id, BookingRef booking, Person driver, Money amount, String reason, String status, String method,
                         String createdAt, String completedAt, String failureReason) {}

    public record Payout(String id, Person host, Money amount, int bookingCount, String destination, String status, String initiatedAt,
                         String paidAt, String failureReason) {}

    public record Retried(String id, String status) {}

    public record Target(String type, String id, String label) {}

    public record Activity(String id, String at, String actor, String action, Target target, String reason) {}

    public record ScheduledPayouts(long count, Money amount) {}

    public record Overview(long pendingListings, long bookingsToday, long activeBookings, long refundsProcessing, long refundsFailed,
                           long payoutsFailed, ScheduledPayouts payoutsScheduled, List<Activity> recentActivity) {}
}
