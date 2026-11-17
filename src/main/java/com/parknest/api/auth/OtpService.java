package com.parknest.api.auth;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.common.Phones;
import com.parknest.api.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and checks 6-digit codes. Only an HMAC of the code is stored. Limits: a few codes per number per window,
 * a wait before resending, and 5 tries per code.
 */
@Service
public class OtpService {

    public enum Purpose { LOGIN, LINK_PHONE }

    public record Sent(String otpRequestId, String maskedPhone, long expiresInSeconds, long resendAfterSeconds) {}

    private final JdbcClient db;
    private final SmsSender sms;
    private final AppProperties.Otp props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(JdbcClient db, SmsSender sms, AppProperties props) {
        this.db = db;
        this.sms = sms;
        this.props = props.otp();
    }

    @Transactional
    public Sent send(String phone, Purpose purpose, String userId) {
        if (!Phones.valid(phone)) throw ApiException.invalid("phone", "Enter a valid 10-digit mobile number");

        OffsetDateTime windowStart = OffsetDateTime.now().minus(props.window());
        record Recent(int count, OffsetDateTime latest) {}
        Recent recent = db.sql("SELECT count(*) AS c, max(created_at) AS latest FROM otp_requests WHERE phone = :phone AND created_at > :since")
                .param("phone", phone).param("since", windowStart)
                .query((rs, n) -> new Recent(rs.getInt("c"), rs.getObject("latest", OffsetDateTime.class)))
                .single();
        if (recent.count() >= props.maxPerWindow()) {
            throw ApiException.tooMany("Too many codes requested for this number. Try again in a few minutes.");
        }
        if (recent.latest() != null && recent.latest().plus(props.resendAfter()).isAfter(OffsetDateTime.now())) {
            throw ApiException.tooMany("Wait a few seconds before asking for another code.");
        }

        String id = Ids.newId("otp");
        String code = props.fixedCode() != null && !props.fixedCode().isBlank() ? props.fixedCode() : String.format("%06d", random.nextInt(1_000_000));
        db.sql("""
                INSERT INTO otp_requests (id, phone, purpose, user_id, code_hash, expires_at)
                VALUES (:id, :phone, :purpose, :user, :hash, :expires)
                """)
                .param("id", id).param("phone", phone).param("purpose", purpose.name()).param("user", userId)
                .param("hash", hash(id, code)).param("expires", OffsetDateTime.now().plus(props.ttl()))
                .update();
        sms.sendOtp(phone, code);
        return new Sent(id, Phones.mask(phone), props.ttl().toSeconds(), props.resendAfter().toSeconds());
    }

    /**
     * Checks the code and marks it used. Wrong codes count as attempts; after the last attempt the code stops working.
     * userId must match for LINK_PHONE codes (null for LOGIN). Runs in its own transaction so a wrong try is counted
     * even when the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(String otpRequestId, String phone, String code, Purpose purpose, String userId) {
        record Row(String phone, String purpose, String userId, String hash, int attempts, OffsetDateTime expiresAt, OffsetDateTime consumedAt) {}
        Row row = db.sql("""
                SELECT phone, purpose, user_id, code_hash, attempts, expires_at, consumed_at
                FROM otp_requests WHERE id = :id FOR UPDATE
                """)
                .param("id", otpRequestId)
                .query((rs, n) -> new Row(rs.getString("phone"), rs.getString("purpose"), rs.getString("user_id"), rs.getString("code_hash"),
                        rs.getInt("attempts"), rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("consumed_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(OtpService::expired);

        boolean sameRequest = row.phone().equals(phone) && row.purpose().equals(purpose.name())
                && (userId == null ? row.userId() == null : userId.equals(row.userId()));
        if (!sameRequest || row.consumedAt() != null || row.expiresAt().isBefore(OffsetDateTime.now())) throw expired();
        if (row.attempts() >= props.maxAttempts()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many tries", "OTP_LOCKED", "otp", "Too many wrong codes. Ask for a new code.");
        }
        if (!MessageDigest.isEqual(row.hash().getBytes(StandardCharsets.UTF_8), hash(otpRequestId, code).getBytes(StandardCharsets.UTF_8))) {
            db.sql("UPDATE otp_requests SET attempts = attempts + 1 WHERE id = :id").param("id", otpRequestId).update();
            throw ApiException.badRequest("OTP_INVALID", "otp", "That code is not right. Check the SMS and try again.");
        }
        db.sql("UPDATE otp_requests SET consumed_at = now() WHERE id = :id").param("id", otpRequestId).update();
    }

    private static ApiException expired() {
        return ApiException.badRequest("OTP_EXPIRED", "otp", "This code has expired. Ask for a new code.");
    }

    private String hash(String otpRequestId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((otpRequestId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash OTP", e);
        }
    }
}
