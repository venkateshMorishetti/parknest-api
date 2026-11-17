package com.parknest.api.payments;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Money back to a driver (table refunds). */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    private String id;

    private String paymentId;
    private String bookingId;
    private long amountPaise;
    private String reason;
    /** DRIVER, HOST, ADMIN or SYSTEM */
    private String initiatedBy;
    /** PROCESSING, REFUNDED or FAILED */
    private String status = "PROCESSING";
    private String providerRefundId;
    private String failureReason;
    private short attempts = 1;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
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
