package com.parknest.api.listings;

import com.parknest.api.users.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A parking spot (table listings). bays = how many cars fit at the same time. Prices are per bay, in paise.
 * Status: DRAFT, NEEDS_APPROVAL (ParkNest reviews it), ACTIVE (bookable), PAUSED (by the host), REJECTED (reviewNote).
 */
@Entity
@Table(name = "listings")
@Getter
@Setter
@NoArgsConstructor
public class Listing {

    @Id
    private String id;

    @Column(name = "host_id")
    private String hostId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", insertable = false, updatable = false)
    private User host;

    private String title;
    private String description;
    private String spotType;
    private short bays = 1;
    private String bayLabel;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> vehicleTypes = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "security_features", columnDefinition = "text[]")
    private List<String> security = new ArrayList<>();

    /** Shown to drivers only after they pay. */
    private String entryInstructions;

    @Column(name = "address_line1")
    private String line1;
    private String area;
    private String city;
    private String state;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 6)
    private String pincode;

    private BigDecimal lat;
    private BigDecimal lng;
    private long pricePerHourPaise;
    private long pricePerDayPaise;

    @Column(name = "is_24x7")
    private boolean is24x7 = true;
    private LocalTime opensAt;
    private LocalTime closesAt;
    private String bookingMode;
    private short cancellationHours = 24;
    private String status = "DRAFT";
    /** NEW, LOCATION_CHANGED or RESUBMITTED, while in review. */
    private String reviewReason;
    /** Why ParkNest did not approve it (REJECTED). */
    private String reviewNote;
    private OffsetDateTime submittedAt;
    private OffsetDateTime reviewedAt;
    private String reviewedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    /** In order; the first is the cover. */
    @ElementCollection
    @CollectionTable(name = "listing_photos", joinColumns = @JoinColumn(name = "listing_id"))
    @OrderColumn(name = "position")
    private List<ListingPhoto> photos = new ArrayList<>();

    public String coverFileId() {
        return photos.isEmpty() ? null : photos.getFirst().getFileId();
    }

    public boolean isActive() {
        return "ACTIVE".equals(status) && deletedAt == null;
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
