package com.parknest.api.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A signed-in device: its refresh token (hashed). Table sessions. */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
public class Session {

    @Id
    private String id;

    private String userId;
    private String refreshTokenHash;
    private String userAgent;

    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress ipAddress;

    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime revokedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
