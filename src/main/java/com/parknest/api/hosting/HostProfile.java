package com.parknest.api.hosting;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Hosting on or off for a user (table host_profiles). No documents: a payout account and the terms are enough. */
@Entity
@Table(name = "host_profiles")
@Getter
@Setter
@NoArgsConstructor
public class HostProfile {

    @Id
    private String userId;

    /** NOT_STARTED or ACTIVE */
    private String status = "NOT_STARTED";
    private String termsVersion;
    private OffsetDateTime termsAcceptedAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public HostProfile(String userId) {
        this.userId = userId;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
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
