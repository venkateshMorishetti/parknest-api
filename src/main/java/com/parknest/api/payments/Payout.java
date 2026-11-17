package com.parknest.api.payments;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Money out to a host (table payouts), covering the bookings in payout_items. */
@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
public class Payout {

    @Id
    private String id;

    private String hostId;
    private String payoutAccountId;
    private long amountPaise;
    /** SCHEDULED, PROCESSING, PAID or FAILED */
    private String status = "SCHEDULED";
    private LocalDate scheduledFor;
    private String providerPayoutId;
    private String utr;
    private String failureReason;
    private short attempts;
    private OffsetDateTime initiatedAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
