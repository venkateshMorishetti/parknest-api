package com.parknest.api.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** How a user signs in: PHONE (the number) or GOOGLE (Google's "sub"). Table auth_identities. */
@Entity
@Table(name = "auth_identities")
@Getter
@Setter
@NoArgsConstructor
public class AuthIdentity {

    @Id
    private String id;

    private String userId;
    private String provider;
    private String providerSubject;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
