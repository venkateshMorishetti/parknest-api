package com.parknest.api.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Settings under "parknest." in application.yml (overridden per environment by env variables). */
@Validated
@ConfigurationProperties(prefix = "parknest")
public record AppProperties(Jwt jwt, Otp otp, Google google, Cors cors, Hosting hosting) {

    /** Access tokens are short-lived JWTs signed with this secret (at least 32 characters). */
    public record Jwt(@NotBlank @Size(min = 32) String secret, Duration accessTtl, Duration refreshTtl, String issuer) {}

    /**
     * OTP codes. pepper is a secret mixed into the stored hash. fixedCode (local and testing only) makes every code that
     * value and logs it instead of sending an SMS.
     */
    public record Otp(@NotBlank String pepper, Duration ttl, Duration resendAfter, int maxAttempts, int maxPerWindow,
                      Duration window, String fixedCode) {}

    /** Google sign-in: the web client id (the token's audience). allowMockTokens accepts "mock-google-id-token" (local only). */
    public record Google(String clientId, boolean allowMockTokens) {}

    public record Cors(List<String> allowedOrigins) {}

    /** Version of the hosting terms the host accepts on /host/start. */
    public record Hosting(String termsVersion) {}
}
