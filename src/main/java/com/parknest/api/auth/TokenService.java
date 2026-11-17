package com.parknest.api.auth;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.config.AppProperties;
import com.parknest.api.users.User;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access tokens: short-lived JWTs (sub = user id, roles). Refresh tokens: random strings, stored only as a SHA-256
 * hash in sessions, swapped for a new one on every refresh.
 */
@Service
public class TokenService {

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    private final JwtEncoder encoder;
    private final SessionRepository sessions;
    private final AppProperties.Jwt props;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, SessionRepository sessions, AppProperties props) {
        this.encoder = encoder;
        this.sessions = sessions;
        this.props = props.jwt();
    }

    @Transactional
    public Tokens issue(User user, String userAgent, InetAddress ip) {
        String refresh = randomToken();
        Session session = new Session();
        session.setId(Ids.newId("ses"));
        session.setUserId(user.getId());
        session.setRefreshTokenHash(sha256(refresh));
        session.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 300)));
        session.setIpAddress(ip);
        session.setExpiresAt(OffsetDateTime.now().plus(props.refreshTtl()));
        sessions.save(session);
        return new Tokens(accessToken(user), refresh, props.accessTtl().toSeconds());
    }

    /** Uses up a refresh token (it stops working) and returns its user id. */
    @Transactional
    public String rotate(String refreshToken) {
        Session session = sessions.findLiveForUpdate(sha256(refreshToken), OffsetDateTime.now())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "SESSION_EXPIRED", null, "Your session has ended. Log in again."));
        session.setRevokedAt(OffsetDateTime.now());
        return session.getUserId();
    }

    @Transactional
    public void revoke(String refreshToken) {
        sessions.findByRefreshTokenHashAndRevokedAtIsNull(sha256(refreshToken)).ifPresent(s -> s.setRevokedAt(OffsetDateTime.now()));
    }

    @Transactional
    public void revokeAll(String userId) {
        sessions.revokeAll(userId, OffsetDateTime.now());
    }

    private String accessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim("roles", user.getRoles())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
