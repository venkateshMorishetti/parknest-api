package com.parknest.api.bookings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A booking or a booking request (table bookings). Amounts in paise, fixed when it is created.
 *  INSTANT:       PENDING_PAYMENT (bay held until holdExpiresAt) -> CONFIRMED, or EXPIRED.
 *  HOST_APPROVAL: PENDING_HOST_APPROVAL -> PENDING_PAYMENT (approved) -> CONFIRMED, or DECLINED.
 *  Then CONFIRMED -> CHECKED_IN -> COMPLETED, or CANCELLED.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    private String id;

    private String bookingRef;
    @Column(name = "listing_id")
    private String listingId;
    @Column(name = "driver_id")
    private String driverId;
    @Column(name = "host_id")
    private String hostId;
    private String bookingMode;
    private String status;
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private String vehicleId;
    private String vehicleType;
    private String vehicleRegistration;
    private long parkingPaise;
    private long serviceFeePaise;
    private long gstPaise;
    private long totalPaise;
    private long hostEarningsPaise;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> priceLines = new ArrayList<>();

    private OffsetDateTime holdExpiresAt;
    private OffsetDateTime hostDecidedAt;
    private String declineReason;
    private String declinedByBookingId;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime checkedInAt;
    private OffsetDateTime checkedOutAt;
    private OffsetDateTime cancelledAt;
    private String cancelledBy;
    private String cancellationReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** How many days are charged (at least 1), for "3 days" on cards. */
    public int chargedDays() {
        long hours = (java.time.Duration.between(startAt, endAt).toMinutes() + 59) / 60;
        return hours < 24 ? 1 : (int) ((hours + 23) / 24);
    }

    public boolean is(String... statuses) {
        for (String s : statuses) if (s.equals(status)) return true;
        return false;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
