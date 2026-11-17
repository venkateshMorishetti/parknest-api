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
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
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

    private final OtpRequestRepository otps;
    private final SmsSender sms;
    private final AppProperties.Otp props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpRequestRepository otps, SmsSender sms, AppProperties props) {
        this.otps = otps;
        this.sms = sms;
        this.props = props.otp();
    }

    @Transactional
    public Sent send(String phone, Purpose purpose, String userId) {
        if (!Phones.valid(phone)) throw ApiException.invalid("phone", "Enter a valid 10-digit mobile number");
        OffsetDateTime now = OffsetDateTime.now();
        if (otps.countByPhoneAndCreatedAtAfter(phone, now.minus(props.window())) >= props.maxPerWindow()) {
            throw ApiException.tooMany("Too many codes requested for this number. Try again in a few minutes.");
        }
        otps.findFirstByPhoneOrderByCreatedAtDesc(phone)
                .filter(last -> last.getCreatedAt().plus(props.resendAfter()).isAfter(now))
                .ifPresent(last -> {
                    throw ApiException.tooMany("Wait a few seconds before asking for another code.");
                });

        String code = props.fixedCode() != null && !props.fixedCode().isBlank() ? props.fixedCode() : String.format("%06d", random.nextInt(1_000_000));
        OtpRequest otp = new OtpRequest();
        otp.setId(Ids.newId("otp"));
        otp.setPhone(phone);
        otp.setPurpose(purpose.name());
        otp.setUserId(userId);
        otp.setCodeHash(hash(otp.getId(), code));
        otp.setExpiresAt(now.plus(props.ttl()));
        otps.save(otp);
        sms.sendOtp(phone, code);
        return new Sent(otp.getId(), Phones.mask(phone), props.ttl().toSeconds(), props.resendAfter().toSeconds());
    }

    /**
     * Checks the code and marks it used. userId must match for LINK_PHONE codes (null for LOGIN). Runs in its own
     * transaction so a wrong try is counted even when the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(String otpRequestId, String phone, String code, Purpose purpose, String userId) {
        OtpRequest otp = otps.findForUpdate(otpRequestId).orElseThrow(OtpService::expired);
        boolean sameRequest = otp.getPhone().equals(phone) && otp.getPurpose().equals(purpose.name()) && Objects.equals(otp.getUserId(), userId);
        if (!sameRequest || otp.getConsumedAt() != null || otp.getExpiresAt().isBefore(OffsetDateTime.now())) throw expired();
        if (otp.getAttempts() >= props.maxAttempts()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many tries", "OTP_LOCKED", "otp", "Too many wrong codes. Ask for a new code.");
        }
        if (!MessageDigest.isEqual(otp.getCodeHash().getBytes(StandardCharsets.UTF_8), hash(otpRequestId, code).getBytes(StandardCharsets.UTF_8))) {
            otp.setAttempts((short) (otp.getAttempts() + 1));
            throw ApiException.badRequest("OTP_INVALID", "otp", "That code is not right. Check the SMS and try again.");
        }
        otp.setConsumedAt(OffsetDateTime.now());
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
