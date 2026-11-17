package com.parknest.api.hosting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Where a host's earnings go (table payout_accounts). The account number is encrypted (PayoutCrypto). */
@Entity
@Table(name = "payout_accounts")
@Getter
@Setter
@NoArgsConstructor
public class PayoutAccount {

    @Id
    private String id;

    private String userId;
    /** UPI or BANK_ACCOUNT */
    private String method;
    private String upiId;
    private String accountHolderName;
    private byte[] accountNumberEncrypted;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 4)
    private String accountNumberLast4;

    private String ifsc;
    /** Masked text for the app: "ve••••sh@okaxis" or "HDFC ••••4410". */
    private String display;
    /** VERIFYING until the ₹1 test transfer confirms it, then VERIFIED (or FAILED). */
    private String status = "VERIFYING";
    private String nameAtBank;
    private OffsetDateTime verifiedAt;
    /** Null while in use; set when the host changes to another account. */
    private OffsetDateTime replacedAt;
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
