package com.parknest.api.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.parknest.api.common.ErrorWriter;
import com.parknest.api.users.AccountStatusFilter;
import com.parknest.api.users.UserRepository;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security. Public: sign-in, search and spot pages, content. Everything else needs a token; /admin needs
 * the ADMIN role. After the token is checked, AccountStatusFilter blocks blocked users and users without a verified
 * mobile number.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ErrorWriter errors, UserRepository users) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/content/**", "/api/v1/meta/**", "/api/v1/listings/**", "/api/v1/locations/**", "/api/v1/files/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/listings/search", "/api/v1/listings/*/quote").permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((req, res, ex) -> errors.write(res, 401, "Unauthorized", "UNAUTHORIZED", "Log in to continue"))
                        .accessDeniedHandler((req, res, ex) -> errors.write(res, 403, "Not allowed", "FORBIDDEN", "You do not have access to this")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> errors.write(res, 401, "Unauthorized", "UNAUTHORIZED", "Log in to continue"))
                        .accessDeniedHandler((req, res, ex) -> errors.write(res, 403, "Not allowed", "FORBIDDEN", "You do not have access to this")))
                .addFilterAfter(new AccountStatusFilter(users, errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Roles come from the "roles" claim: ["ADMIN"] -> ROLE_ADMIN. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    SecretKey jwtKey(AppProperties props) {
        return new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.cors().allowedOrigins());
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        cors.addExposedHeader("X-Request-Id");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
