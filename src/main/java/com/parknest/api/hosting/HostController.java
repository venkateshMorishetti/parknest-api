package com.parknest.api.hosting;

import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/host")
public class HostController {

    private final HostingService hosting;
    private final HostListingService listings;

    public HostController(HostingService hosting, HostListingService listings) {
        this.hosting = hosting;
        this.listings = listings;
    }

    /** GET /host/onboarding */
    @GetMapping("/onboarding")
    public ResponseEntity<ApiResponse<HostDtos.Onboarding>> onboarding(@AuthenticationPrincipal Jwt jwt) {
        return Responses.ok("Hosting status fetched", hosting.onboarding(jwt.getSubject()));
    }

    /** POST /host/onboarding */
    @PostMapping("/onboarding")
    public ResponseEntity<ApiResponse<HostDtos.Onboarding>> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HostDtos.OnboardingRequest req) {
        return Responses.created("Hosting is on", hosting.start(jwt.getSubject(), req));
    }

    /** PUT /host/payout-account */
    @PutMapping("/payout-account")
    public ResponseEntity<ApiResponse<HostDtos.PayoutAccountView>> payout(@AuthenticationPrincipal Jwt jwt, @RequestBody HostDtos.PayoutRequest req) {
        return Responses.ok("Payout account updated", hosting.updatePayout(jwt.getSubject(), req));
    }

    /** GET /host/settings */
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<HostDtos.HostSettings>> settings(@AuthenticationPrincipal Jwt jwt) {
        return Responses.ok("Settings fetched", hosting.settings(jwt.getSubject()));
    }

    /** PUT /host/settings/notifications */
    @PutMapping("/settings/notifications")
    public ResponseEntity<ApiResponse<HostDtos.NotificationSettings>> notifications(@AuthenticationPrincipal Jwt jwt,
                                                                                    @Valid @RequestBody HostDtos.NotificationSettings req) {
        return Responses.ok("Notification settings saved", hosting.updateNotifications(jwt.getSubject(), req));
    }

    /** POST /host/listings */
    @PostMapping("/listings")
    public ResponseEntity<ApiResponse<HostDtos.CreatedListing>> create(@AuthenticationPrincipal Jwt jwt, @RequestBody HostDtos.ListingRequest req) {
        return Responses.of(HttpStatus.CREATED, "Listing submitted for review", listings.create(jwt.getSubject(), req), Map.of());
    }

    /** GET /host/listings?status= */
    @GetMapping("/listings")
    public ResponseEntity<ApiResponse<List<HostDtos.ManagedListing>>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        HostListingService.Page page = listings.list(jwt.getSubject(), status);
        int n = page.items().size();
        return Responses.ok(n == 1 ? "1 listing" : n + " listings", page.items(), Map.of("counts", page.counts()));
    }

    /** GET /host/listings/{id} */
    @GetMapping("/listings/{id}")
    public ResponseEntity<ApiResponse<HostDtos.EditableListing>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Listing fetched", listings.get(jwt.getSubject(), id));
    }

    /** PUT /host/listings/{id} */
    @PutMapping("/listings/{id}")
    public ResponseEntity<ApiResponse<HostDtos.UpdatedListing>> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                      @RequestBody HostDtos.ListingRequest req) {
        HostDtos.UpdatedListing result = listings.update(jwt.getSubject(), id, req);
        return Responses.ok(result.reviewEstimate() != null ? "Listing updated and sent for review" : "Listing updated", result);
    }

    /** PATCH /host/listings/{id}/status */
    @PatchMapping("/listings/{id}/status")
    public ResponseEntity<ApiResponse<HostDtos.StatusResult>> status(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                    @RequestBody HostDtos.StatusRequest req) {
        HostDtos.StatusResult result = listings.setStatus(jwt.getSubject(), id, req.status());
        return Responses.ok("PAUSED".equals(result.status()) ? "Listing paused" : "Listing resumed", result);
    }
}
