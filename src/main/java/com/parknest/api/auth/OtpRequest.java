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

/** One OTP code sent by SMS. Only an HMAC of the code is stored. Table otp_requests. */
@Entity
@Table(name = "otp_requests")
@Getter
@Setter
@NoArgsConstructor
public class OtpRequest {

    @Id
    private String id;

    private String phone;
    /** LOGIN or LINK_PHONE */
    private String purpose;
    /** LINK_PHONE only: the signed-in user adding this number. */
    private String userId;
    private String codeHash;
    private short attempts;
    private OffsetDateTime expiresAt;
    private OffsetDateTime consumedAt;

    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress ipAddress;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
