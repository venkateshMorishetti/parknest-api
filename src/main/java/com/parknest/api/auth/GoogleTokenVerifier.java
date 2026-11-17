package com.parknest.api.auth;

import com.parknest.api.common.ApiException;
import com.parknest.api.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks the ID token from Google Identity Services: signed by Google (keys from Google's JWKS), issued by Google,
 * meant for our client id, not expired, and with a verified email.
 */
@Component
public class GoogleTokenVerifier {

    public record GoogleUser(String subject, String email, boolean emailVerified, String name, String picture) {}

    private static final String MOCK_TOKEN = "mock-google-id-token";

    private final AppProperties.Google props;
    private final NimbusJwtDecoder decoder;

    public GoogleTokenVerifier(AppProperties props) {
        this.props = props.google();
        this.decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(this.props.clientId())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        OAuth2TokenValidator<Jwt> issuer = jwt -> {
            String iss = jwt.getClaimAsString("iss");
            return "https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer, audience));
    }

    public GoogleUser verify(String idToken) {
        if (props.allowMockTokens() && MOCK_TOKEN.equals(idToken)) {
            // Local development only: the frontend's mock Google button.
            return new GoogleUser("mock-google-sub-venkatesh", "venkatesh@gmail.com", true, "Venkatesh Kumar", null);
        }
        if (props.clientId() == null || props.clientId().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not set up", "GOOGLE_NOT_CONFIGURED", null,
                    "Google sign-in is not available yet. Use your mobile number.");
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");  // boolean, or the string "true" in older tokens
            return new GoogleUser(jwt.getSubject(), jwt.getClaimAsString("email"), Boolean.TRUE.equals(verified) || "true".equals(verified),
                    jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google sign-in failed", "GOOGLE_TOKEN_INVALID", null,
                    "We could not confirm your Google account. Try again.");
        }
    }
}
