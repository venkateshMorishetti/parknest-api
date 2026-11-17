package com.parknest.api.bookings;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Driver bookings and payment confirmation. */
@RestController
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** POST /bookings */
    @PostMapping("/bookings")
    public ResponseEntity<ApiResponse<BookingDtos.Hold>> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingDtos.CreateRequest req) {
        BookingDtos.Hold hold = bookings.create(jwt.getSubject(), req);
        return Responses.created("PENDING_HOST_APPROVAL".equals(hold.status()) ? "Request sent to the host" : "Spot held", hold);
    }

    /** GET /bookings?tab= */
    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<List<BookingDtos.Summary>>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String tab) {
        BookingService.Page page = bookings.list(jwt.getSubject(), tab);
        return Responses.ok("Bookings fetched", page.items(), Map.of("counts", page.counts()));
    }

    /** GET /bookings/{id} */
    @GetMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingDtos.Detail>> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Booking fetched", bookings.detail(jwt.getSubject(), id));
    }

    /** PATCH /bookings/{id} */
    @PatchMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingDtos.Hold>> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                @Valid @RequestBody BookingDtos.UpdateRequest req) {
        return Responses.ok("Booking updated", bookings.update(jwt.getSubject(), id, req));
    }

    /** POST /bookings/{id}/check-in */
    @PostMapping("/bookings/{id}/check-in")
    public ResponseEntity<ApiResponse<BookingDtos.StatusChange>> checkIn(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Checked in", bookings.checkIn(jwt.getSubject(), id));
    }

    /** POST /bookings/{id}/check-out */
    @PostMapping("/bookings/{id}/check-out")
    public ResponseEntity<ApiResponse<BookingDtos.StatusChange>> checkOut(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Checked out", bookings.checkOut(jwt.getSubject(), id));
    }

    /** GET /bookings/{id}/cancellation */
    @GetMapping("/bookings/{id}/cancellation")
    public ResponseEntity<ApiResponse<BookingDtos.CancellationPreview>> cancellation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return Responses.ok("Cancellation details fetched", bookings.cancellationPreview(jwt.getSubject(), id));
    }

    /** POST /bookings/{id}/cancel */
    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<ApiResponse<BookingDtos.CancelResult>> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                                        @RequestBody(required = false) BookingDtos.CancelRequest req) {
        return Responses.ok("Booking cancelled", bookings.cancel(jwt.getSubject(), id, req));
    }

    /** POST /payments/verify */
    @PostMapping("/payments/verify")
    public ResponseEntity<ApiResponse<BookingDtos.PaymentVerifyResult>> verify(@AuthenticationPrincipal Jwt jwt,
                                                                               @Valid @RequestBody BookingDtos.PaymentVerifyRequest req) {
        return Responses.of(HttpStatus.OK, "Payment confirmed", bookings.verifyPayment(jwt.getSubject(), req), Map.of());
    }
}
