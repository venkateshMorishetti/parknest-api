package com.parknest.api.bookings;

import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The host's dashboard, requests, bookings and earnings. */
@RestController
@RequestMapping("/host")
public class HostBookingController {

    private final HostBookingService host;

    public HostBookingController(HostBookingService host) {
        this.host = host;
    }

    /** GET /host/dashboard */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<BookingDtos.Dashboard>> dashboard(@AuthenticationPrincipal Jwt jwt) {
        return Responses.ok("Host dashboard fetched", host.dashboard(jwt.getSubject()));
    }

    /** POST /host/booking-requests/{id}/decision */
    @PostMapping("/booking-requests/{id}/decision")
    public ResponseEntity<ApiResponse<BookingDtos.DecisionResult>> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                          @RequestBody(required = false) BookingDtos.DecisionRequest req) {
        BookingDtos.DecisionResult result = host.decide(jwt.getSubject(), id, req);
        return Responses.ok("APPROVED".equals(result.status()) ? "Request approved" : "Request declined", result);
    }

    /** GET /host/bookings?tab= */
    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<List<BookingDtos.HostBooking>>> bookings(@AuthenticationPrincipal Jwt jwt,
                                                                              @RequestParam(required = false) String tab) {
        HostBookingService.Page page = host.list(jwt.getSubject(), tab);
        return Responses.ok("Bookings fetched", page.items(), Map.of("counts", page.counts()));
    }

    /** GET /host/earnings */
    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<BookingDtos.Earnings>> earnings(@AuthenticationPrincipal Jwt jwt) {
        return Responses.ok("Earnings fetched", host.earnings(jwt.getSubject()));
    }
}
