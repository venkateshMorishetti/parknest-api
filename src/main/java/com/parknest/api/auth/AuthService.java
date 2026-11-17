package com.parknest.api.auth;

import com.parknest.api.auth.AuthDtos.AuthSession;
import com.parknest.api.auth.AuthDtos.OtpSendResult;
import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.common.Phones;
import com.parknest.api.users.User;
import com.parknest.api.users.UserDtos.AppUser;
import com.parknest.api.users.UserRepository;
import com.parknest.api.users.UserService;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in with phone OTP or Google, refresh and logout, and adding a mobile number to a Google account.
 * New users are created on first sign-in (no separate sign-up).
 */
@Service
public class AuthService {

    static final String NEW_USER_NAME = "ParkNest user";

    private final OtpService otp;
    private final TokenService tokens;
    private final GoogleTokenVerifier google;
    private final UserRepository users;
    private final UserService userService;
    private final JdbcClient db;

    public AuthService(OtpService otp, TokenService tokens, GoogleTokenVerifier google, UserRepository users, UserService userService, JdbcClient db) {
        this.otp = otp;
        this.tokens = tokens;
        this.google = google;
        this.users = users;
        this.userService = userService;
        this.db = db;
    }

    public OtpSendResult sendLoginOtp(String phone) {
        OtpService.Sent sent = otp.send(phone, OtpService.Purpose.LOGIN, null);
        return new OtpSendResult(sent.otpRequestId(), sent.maskedPhone(), sent.expiresInSeconds(), sent.resendAfterSeconds());
    }

    /** Logging in with the phone also verifies it. Creates the account the first time. */
    @Transactional
    public AuthSession verifyLoginOtp(AuthDtos.OtpVerifyRequest req, String userAgent, String ip) {
        otp.verify(req.otpRequestId(), req.phone(), req.otp(), OtpService.Purpose.LOGIN, null);
        Optional<User> existing = users.findByPhone(req.phone());
        String userId = existing.map(User::id).orElseGet(() -> {
            String id = Ids.newId("usr");
            users.insert(id, NEW_USER_NAME, null, false, req.phone(), null);
            return id;
        });
        upsertIdentity(userId, "PHONE", req.phone());
        return session(userId, existing.isEmpty(), userAgent, ip);
    }

    /**
     * Google sign-in. Finds the user by Google account, or by the same verified email, or creates one. A new Google
     * user has no phone yet: the app sends them to /verify-phone, and the API answers 403 PHONE_NOT_VERIFIED until then.
     */
    @Transactional
    public AuthSession loginWithGoogle(String idToken, String userAgent, String ip) {
        GoogleTokenVerifier.GoogleUser g = google.verify(idToken);
        Optional<String> byIdentity = db.sql("SELECT user_id FROM auth_identities WHERE provider = 'GOOGLE' AND provider_subject = :sub")
                .param("sub", g.subject()).query(String.class).optional();
        boolean isNew = false;
        String userId;
        if (byIdentity.isPresent()) {
            userId = byIdentity.get();
        } else {
            Optional<User> byEmail = g.emailVerified() && g.email() != null ? users.findByEmail(g.email()).filter(u -> u.emailVerifiedAt() != null) : Optional.empty();
            if (byEmail.isPresent()) {
                userId = byEmail.get().id();
            } else {
                userId = Ids.newId("usr");
                if (g.emailVerified() && g.email() != null) {
                    // Google proved this person owns the email. Another account that only typed it in (unverified)
                    // loses it, so the email cannot be used to take over this Google account later.
                    db.sql("UPDATE users SET email = NULL WHERE lower(email) = lower(:email) AND email_verified_at IS NULL")
                            .param("email", g.email()).update();
                }
                String name = g.name() != null && !g.name().isBlank() ? g.name() : NEW_USER_NAME;
                users.insert(userId, name, g.emailVerified() ? g.email() : null, g.emailVerified(), null, g.picture());
                isNew = true;
            }
            upsertIdentity(userId, "GOOGLE", g.subject());
        }
        return session(userId, isNew, userAgent, ip);
    }

    @Transactional
    public AuthSession refresh(String refreshToken, String userAgent, String ip) {
        String userId = tokens.rotate(refreshToken);
        return session(userId, false, userAgent, ip);
    }

    public void logout(String refreshToken) {
        tokens.revoke(refreshToken);
    }

    // ---------- Adding a mobile number to a signed-in account ----------

    public OtpSendResult sendPhoneLinkOtp(String userId, String phone) {
        if (!Phones.valid(phone)) throw ApiException.invalid("phone", "Enter a valid 10-digit mobile number");
        ensurePhoneFree(userId, phone);
        OtpService.Sent sent = otp.send(phone, OtpService.Purpose.LINK_PHONE, userId);
        return new OtpSendResult(sent.otpRequestId(), sent.maskedPhone(), sent.expiresInSeconds(), sent.resendAfterSeconds());
    }

    @Transactional
    public AppUser verifyPhoneLink(String userId, String otpRequestId, String phone, String code) {
        otp.verify(otpRequestId, phone, code, OtpService.Purpose.LINK_PHONE, userId);
        ensurePhoneFree(userId, phone);
        users.setVerifiedPhone(userId, phone);
        upsertIdentity(userId, "PHONE", phone);
        return userService.me(userId);
    }

    private void ensurePhoneFree(String userId, String phone) {
        users.findByPhone(phone).filter(u -> !u.id().equals(userId)).ifPresent(u -> {
            throw ApiException.conflict("PHONE_IN_USE", "phone", "This number belongs to another ParkNest account. Log in with that number instead.");
        });
    }

    private void upsertIdentity(String userId, String provider, String subject) {
        db.sql("""
                INSERT INTO auth_identities (id, user_id, provider, provider_subject, last_used_at)
                VALUES (:id, :user, :provider, :subject, now())
                ON CONFLICT (user_id, provider) DO UPDATE SET provider_subject = EXCLUDED.provider_subject, last_used_at = now()
                """)
                .param("id", Ids.newId("aid")).param("user", userId).param("provider", provider).param("subject", subject)
                .update();
    }

    private AuthSession session(String userId, boolean isNew, String userAgent, String ip) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (user.blocked()) throw ApiException.forbidden("ACCOUNT_BLOCKED", "Your account is blocked. Contact support.");
        users.touch(userId);
        TokenService.Tokens t = tokens.issue(user, userAgent, ip);
        return new AuthSession("Bearer", t.accessToken(), t.refreshToken(), t.expiresInSeconds(), isNew, userService.toAppUser(user));
    }
}
