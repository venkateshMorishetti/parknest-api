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
import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.Optional;
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
    private final AuthIdentityRepository identities;
    private final UserService userService;

    public AuthService(OtpService otp, TokenService tokens, GoogleTokenVerifier google, UserRepository users,
                       AuthIdentityRepository identities, UserService userService) {
        this.otp = otp;
        this.tokens = tokens;
        this.google = google;
        this.users = users;
        this.identities = identities;
        this.userService = userService;
    }

    public OtpSendResult sendLoginOtp(String phone) {
        return result(otp.send(phone, OtpService.Purpose.LOGIN, null));
    }

    /** Logging in with the phone also verifies it. Creates the account the first time. */
    @Transactional
    public AuthSession verifyLoginOtp(AuthDtos.OtpVerifyRequest req, String userAgent, InetAddress ip) {
        otp.verify(req.otpRequestId(), req.phone(), req.otp(), OtpService.Purpose.LOGIN, null);
        Optional<User> existing = users.findByPhone(req.phone());
        User user = existing.orElseGet(() -> {
            User created = new User(Ids.newId("usr"), NEW_USER_NAME);
            created.setPhone(req.phone());
            created.setPhoneVerifiedAt(OffsetDateTime.now());
            return users.save(created);
        });
        linkIdentity(user.getId(), "PHONE", req.phone());
        return session(user, existing.isEmpty(), userAgent, ip);
    }

    /**
     * Google sign-in. Finds the user by Google account, or by the same verified email, or creates one. A new Google
     * user has no phone yet: the app sends them to /verify-phone, and the API answers 403 PHONE_NOT_VERIFIED until then.
     */
    @Transactional
    public AuthSession loginWithGoogle(String idToken, String userAgent, InetAddress ip) {
        GoogleTokenVerifier.GoogleUser g = google.verify(idToken);
        Optional<AuthIdentity> identity = identities.findByProviderAndProviderSubject("GOOGLE", g.subject());
        if (identity.isPresent()) {
            identity.get().setLastUsedAt(OffsetDateTime.now());
            return session(users.findById(identity.get().getUserId()).orElseThrow(() -> ApiException.notFound("User")), false, userAgent, ip);
        }
        boolean verifiedEmail = g.emailVerified() && g.email() != null;
        Optional<User> byEmail = verifiedEmail ? users.findByEmailIgnoreCase(g.email()).filter(User::isEmailVerified) : Optional.empty();
        User user;
        if (byEmail.isPresent()) {
            user = byEmail.get();
        } else {
            if (verifiedEmail) {
                // Google proved this person owns the email. An account that only typed it in (unverified) loses it,
                // so the email cannot be used to take over this Google account later.
                users.clearUnverifiedEmail(g.email());
            }
            user = new User(Ids.newId("usr"), g.name() != null && !g.name().isBlank() ? g.name() : NEW_USER_NAME);
            if (verifiedEmail) {
                user.setEmail(g.email());
                user.setEmailVerifiedAt(OffsetDateTime.now());
            }
            user.setAvatarUrl(g.picture());
            user = users.save(user);
        }
        linkIdentity(user.getId(), "GOOGLE", g.subject());
        return session(user, byEmail.isEmpty(), userAgent, ip);
    }

    @Transactional
    public AuthSession refresh(String refreshToken, String userAgent, InetAddress ip) {
        String userId = tokens.rotate(refreshToken);
        return session(users.findById(userId).orElseThrow(() -> ApiException.notFound("User")), false, userAgent, ip);
    }

    public void logout(String refreshToken) {
        tokens.revoke(refreshToken);
    }

    // ---------- Adding a mobile number to a signed-in account ----------

    public OtpSendResult sendPhoneLinkOtp(String userId, String phone) {
        if (!Phones.valid(phone)) throw ApiException.invalid("phone", "Enter a valid 10-digit mobile number");
        ensurePhoneFree(userId, phone);
        return result(otp.send(phone, OtpService.Purpose.LINK_PHONE, userId));
    }

    @Transactional
    public AppUser verifyPhoneLink(String userId, String otpRequestId, String phone, String code) {
        otp.verify(otpRequestId, phone, code, OtpService.Purpose.LINK_PHONE, userId);
        ensurePhoneFree(userId, phone);
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setPhone(phone);
        user.setPhoneVerifiedAt(OffsetDateTime.now());
        users.saveAndFlush(user);
        linkIdentity(userId, "PHONE", phone);
        return userService.toAppUser(user);
    }

    private void ensurePhoneFree(String userId, String phone) {
        users.findByPhone(phone).filter(u -> !u.getId().equals(userId)).ifPresent(u -> {
            throw ApiException.conflict("PHONE_IN_USE", "phone", "This number belongs to another ParkNest account. Log in with that number instead.");
        });
    }

    /** One identity per user and provider; a new phone number replaces the old one. */
    private void linkIdentity(String userId, String provider, String subject) {
        AuthIdentity identity = identities.findByUserIdAndProvider(userId, provider).orElseGet(() -> {
            AuthIdentity created = new AuthIdentity();
            created.setId(Ids.newId("aid"));
            created.setUserId(userId);
            created.setProvider(provider);
            return created;
        });
        identity.setProviderSubject(subject);
        identity.setLastUsedAt(OffsetDateTime.now());
        identities.saveAndFlush(identity);
    }

    private AuthSession session(User user, boolean isNew, String userAgent, InetAddress ip) {
        if (user.isBlocked()) throw ApiException.forbidden("ACCOUNT_BLOCKED", "Your account is blocked. Contact support.");
        user.setLastActiveAt(OffsetDateTime.now());
        users.saveAndFlush(user);
        TokenService.Tokens t = tokens.issue(user, userAgent, ip);
        return new AuthSession("Bearer", t.accessToken(), t.refreshToken(), t.expiresInSeconds(), isNew, userService.toAppUser(user));
    }

    private static OtpSendResult result(OtpService.Sent sent) {
        return new OtpSendResult(sent.otpRequestId(), sent.maskedPhone(), sent.expiresInSeconds(), sent.resendAfterSeconds());
    }
}
