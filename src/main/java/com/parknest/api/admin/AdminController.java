package com.parknest.api.admin;

import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin panel. SecurityConfig limits /admin/** to the ADMIN role. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<AdminDtos.Overview>> overview() {
        return Responses.ok("Overview fetched", admin.overview());
    }

    @GetMapping("/listings")
    public ResponseEntity<ApiResponse<List<AdminDtos.Listing>>> listings(@RequestParam(required = false) String status) {
        AdminService.Page<AdminDtos.Listing> page = admin.listings(status);
        return Responses.ok(page.items().size() + " listings", page.items(), page.meta());
    }

    @GetMapping("/listings/{id}")
    public ResponseEntity<ApiResponse<AdminDtos.Listing>> listing(@PathVariable String id) {
        return Responses.ok("Listing fetched", admin.listing(id));
    }

    @PostMapping("/listings/{id}/decision")
    public ResponseEntity<ApiResponse<AdminDtos.DecisionResult>> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                        @RequestBody(required = false) AdminDtos.DecisionRequest req) {
        AdminDtos.DecisionResult result = admin.decideListing(jwt.getSubject(), id, req);
        return Responses.ok("ACTIVE".equals(result.status()) ? "Listing approved" : "Listing rejected", result);
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<AdminDtos.User>>> users(@RequestParam(required = false) String q) {
        AdminService.Page<AdminDtos.User> page = admin.users(q);
        return Responses.ok(page.items().size() + " users", page.items(), page.meta());
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<ApiResponse<AdminDtos.UserUpdateResult>> updateUser(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                              @RequestBody(required = false) AdminDtos.UserUpdate req) {
        AdminDtos.UserUpdateResult result = admin.updateUser(jwt.getSubject(), id, req);
        return Responses.ok("BLOCKED".equals(result.status()) ? "User blocked" : "User unblocked", result);
    }

    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<List<AdminDtos.Booking>>> bookings(@RequestParam(required = false) String q,
                                                                        @RequestParam(required = false) String status) {
        AdminService.Page<AdminDtos.Booking> page = admin.bookings(q, status);
        return Responses.ok(page.items().size() + " bookings", page.items(), page.meta());
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<ApiResponse<AdminDtos.CancelResult>> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                      @RequestBody(required = false) AdminDtos.CancelRequest req) {
        return Responses.ok("Booking cancelled by ParkNest", admin.cancelBooking(jwt.getSubject(), id, req));
    }

    @GetMapping("/refunds")
    public ResponseEntity<ApiResponse<List<AdminDtos.Refund>>> refunds() {
        AdminService.Page<AdminDtos.Refund> page = admin.refunds();
        return Responses.ok(page.items().size() + " refunds", page.items(), page.meta());
    }

    @PostMapping("/refunds/{id}/retry")
    public ResponseEntity<ApiResponse<AdminDtos.Retried>> retryRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Refund sent again", admin.retryRefund(jwt.getSubject(), id));
    }

    @GetMapping("/payouts")
    public ResponseEntity<ApiResponse<List<AdminDtos.Payout>>> payouts() {
        AdminService.Page<AdminDtos.Payout> page = admin.payouts();
        return Responses.ok(page.items().size() + " payouts", page.items(), page.meta());
    }

    @PostMapping("/payouts/{id}/retry")
    public ResponseEntity<ApiResponse<AdminDtos.Retried>> retryPayout(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Payout sent again", admin.retryPayout(jwt.getSubject(), id));
    }

    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<List<AdminDtos.Activity>>> activity() {
        return Responses.ok("Activity fetched", admin.activity(100));
    }
}
