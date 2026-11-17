package com.parknest.api.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Everyone: drivers, hosts and the admin (table users). */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    private String id;

    private String name;
    private String email;
    private OffsetDateTime emailVerifiedAt;
    /** Saved only once verified by OTP; null for a Google sign-in that has not added one yet. */
    private String phone;
    private OffsetDateTime phoneVerifiedAt;
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> roles = new ArrayList<>();

    private String status = "ACTIVE";
    private String blockedReason;
    private OffsetDateTime blockedAt;
    private OffsetDateTime lastActiveAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public User(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isBlocked() {
        return "BLOCKED".equals(status);
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
