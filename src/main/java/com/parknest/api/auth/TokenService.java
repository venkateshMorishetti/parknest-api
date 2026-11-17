package com.parknest.api.auth;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.config.AppProperties;
import com.parknest.api.users.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient db;
    private final AppProperties.Jwt props;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, JdbcClient db, AppProperties props) {
        this.encoder = encoder;
        this.db = db;
        this.props = props.jwt();
    }

    @Transactional
    public Tokens issue(User user, String userAgent, String ip) {
        String refresh = randomToken();
        db.sql("""
                INSERT INTO sessions (id, user_id, refresh_token_hash, user_agent, ip_address, expires_at)
                VALUES (:id, :user, :hash, :agent, CAST(:ip AS inet), :expires)
                """)
                .param("id", Ids.newId("ses")).param("user", user.id()).param("hash", sha256(refresh))
                .param("agent", userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 300)))
                .param("ip", ip).param("expires", OffsetDateTime.now().plus(props.refreshTtl()))
                .update();
        return new Tokens(accessToken(user), refresh, props.accessTtl().toSeconds());
    }

    /** Swaps a refresh token for new tokens; the old one stops working. Returns the user id. */
    @Transactional
    public String rotate(String refreshToken) {
        String userId = db.sql("""
                UPDATE sessions SET revoked_at = now()
                WHERE refresh_token_hash = :hash AND revoked_at IS NULL AND expires_at > now()
                RETURNING user_id
                """)
                .param("hash", sha256(refreshToken))
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "SESSION_EXPIRED", null, "Your session has ended. Log in again."));
        return userId;
    }

    public void revoke(String refreshToken) {
        db.sql("UPDATE sessions SET revoked_at = now() WHERE refresh_token_hash = :hash AND revoked_at IS NULL")
                .param("hash", sha256(refreshToken)).update();
    }

    public void revokeAll(String userId) {
        db.sql("UPDATE sessions SET revoked_at = now() WHERE user_id = :user AND revoked_at IS NULL").param("user", userId).update();
    }

    private String accessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.id())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim("roles", user.roles())
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
