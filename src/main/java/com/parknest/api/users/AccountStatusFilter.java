package com.parknest.api.users;

import com.parknest.api.common.ErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the access token is checked:
 *  - a deleted user gets 401, a blocked user 403 ACCOUNT_BLOCKED;
 *  - a user without a verified mobile number (Google sign-in) gets 403 PHONE_NOT_VERIFIED everywhere except the
 *    calls needed to add one (GET/PUT /users/me and /users/me/phone/...). The app then shows /verify-phone.
 * Not a Spring bean on purpose, so it runs only inside the security chain.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final ErrorWriter errors;

    public AccountStatusFilter(UserRepository users, ErrorWriter errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UserRepository.AccountState> state = users.accountState(token.getName());
        if (state.isEmpty()) {
            errors.write(response, 401, "Unauthorized", "UNAUTHORIZED", "Log in to continue");
            return;
        }
        if ("BLOCKED".equals(state.get().status())) {
            errors.write(response, 403, "Account blocked", "ACCOUNT_BLOCKED", "Your account is blocked. Contact support.");
            return;
        }
        if (!state.get().phoneVerified() && !allowedWithoutPhone(request)) {
            errors.write(response, 403, "Mobile number needed", "PHONE_NOT_VERIFIED", "Add and verify your mobile number first");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedWithoutPhone(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/users/me/phone/")
                || (path.equals("/api/v1/users/me") && ("GET".equals(request.getMethod()) || "PUT".equals(request.getMethod())));
    }
}
