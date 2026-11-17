package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.parknest.api.bookings.PayoutService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminApiTest extends IntegrationTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private final OffsetDateTime start = OffsetDateTime.now(IST).plusDays(3).truncatedTo(ChronoUnit.HOURS);

    @Autowired PayoutService payoutService;

    private String admin;
    private String adminId;
    private String hostToken;

    @BeforeEach
    void setUp() throws Exception {
        adminId = userIdOf(loginWithPhone("+919800000001"));
        jdbc.update("UPDATE users SET name = 'Venkatesh Kumar', roles = '{ADMIN}' WHERE id = ?", adminId);
        admin = loginWithPhone("+919800000001"); // a new token carries the ADMIN role
        hostToken = host("+919876543210");
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void onlyAdmins() throws Exception {
        Res res = get_("/admin/overview", hostToken);
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(get_("/admin/overview", admin).status()).isEqualTo(200);
    }

    @Test
    void reviewingSpots() throws Exception {
        String first = post_("/host/listings", hostToken, spot(hostToken, "Prestige Lakeside Basement", 12.9257, 77.6766, 3, "INSTANT")).data().get("id").asText();
        String second = post_("/host/listings", hostToken, spot(hostToken, "Corner shop frontage", 12.9116, 77.6389, 1, "INSTANT")).data().get("id").asText();

        Res queue = get_("/admin/listings", admin);
        assertThat(queue.data()).hasSize(2);
        assertThat(queue.body().get("meta").get("counts").get("NEEDS_APPROVAL").asInt()).isEqualTo(2);
        JsonNode detail = get_("/admin/listings/" + first, admin).data();
        assertThat(detail.get("reviewReason").asText()).isEqualTo("NEW");
        assertThat(detail.get("host").get("isVerified").asBoolean()).isTrue();
        assertThat(detail.get("pricing").get("perDay").asInt()).isEqualTo(150);
        assertThat(detail.get("photos").get(0).get("url").asText()).startsWith("/api/v1/files/");
        assertThat(get_("/admin/overview", admin).data().get("pendingListings").asInt()).isEqualTo(2);

        assertThat(post_("/admin/listings/" + second + "/decision", admin, body("decision", "REJECT", "reason", "No")).status()).isEqualTo(422);
        Res rejected = post_("/admin/listings/" + second + "/decision", admin, body("decision", "REJECT", "reason", "The spot is on a public road."));
        assertThat(rejected.data().get("status").asText()).isEqualTo("REJECTED");
        assertThat(get_("/host/listings", hostToken).body().toString()).contains("The spot is on a public road.");

        Res approved = post_("/admin/listings/" + first + "/decision", admin, body("decision", "APPROVE", "reason", null));
        assertThat(approved.data().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(post_("/admin/listings/" + first + "/decision", admin, body("decision", "APPROVE")).errorCode()).isEqualTo("ALREADY_DECIDED");
        assertThat(get_("/admin/listings/" + first, admin).data().get("decision").get("decidedBy").asText()).isEqualTo("Venkatesh Kumar");

        JsonNode activity = get_("/admin/activity", admin).data();
        assertThat(activity).hasSize(2);
        assertThat(activity.get(0).get("action").asText()).isEqualTo("LISTING_APPROVED");
        assertThat(activity.get(1).get("target").get("label").asText()).isEqualTo("Corner shop frontage");
        assertThat(activity.get(1).get("reason").asText()).isEqualTo("The spot is on a public road.");
        assertThat(get_("/admin/overview", admin).data().get("recentActivity")).hasSize(2);
    }

    @Test
    void blockingUsers() throws Exception {
        String driver = loginWithPhone("+919845011100");
        String driverId = userIdOf(driver);

        Res found = get_("/admin/users?q=98450", admin);
        assertThat(found.data()).hasSize(1);
        assertThat(found.data().get(0).get("hostStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(get_("/admin/users", admin).data()).hasSize(3);

        assertThat(patch_("/admin/users/" + driverId, admin, body("status", "BLOCKED", "reason", null)).status()).isEqualTo(422);
        assertThat(patch_("/admin/users/" + adminId, admin, body("status", "BLOCKED", "reason", "Testing")).errorCode()).isEqualTo("CANNOT_BLOCK_SELF");
        Res blocked = patch_("/admin/users/" + driverId, admin, body("status", "BLOCKED", "reason", "Repeated no-shows"));
        assertThat(blocked.data().get("status").asText()).isEqualTo("BLOCKED");
        assertThat(get_("/users/me", driver).errorCode()).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, driverId)).isZero();

        assertThat(patch_("/admin/users/" + driverId, admin, body("status", "ACTIVE")).data().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(get_("/users/me", driver).status()).isEqualTo(200);
        assertThat(get_("/admin/activity", admin).data().get(0).get("action").asText()).isEqualTo("USER_UNBLOCKED");
    }

    @Test
    void cancellingBookingsAndRetryingRefunds() throws Exception {
        String spot = liveSpot(hostToken, "Sunrise Apts – Covered Bay", 12.9116, 77.6389, 1, "INSTANT");
        String driver = loginWithPhone("+919845011100");
        Res hold = post_("/bookings", driver, body("listingId", spot, "vehicle", Map.of("type", "SEDAN", "registrationNumber", "KA05MN3001"),
                "startAt", start.toString(), "endAt", start.plusHours(63).toString()));
        String id = hold.data().get("bookingId").asText();
        String ref = hold.data().get("bookingRef").asText();
        String orderId = hold.data().get("payment").get("orderId").asText();
        post_("/payments/verify", driver, body("bookingId", id, "orderId", orderId, "paymentId", "pay_a1", "signature", "mock_sig_" + orderId, "paymentMethod", "CARD"));

        assertThat(get_("/admin/bookings?q=" + ref.toLowerCase(), admin).data()).hasSize(1);
        assertThat(get_("/admin/bookings?q=sunrise&status=CONFIRMED", admin).data()).hasSize(1);
        assertThat(get_("/admin/bookings?status=CHECKED_IN", admin).data()).isEmpty();
        assertThat(get_("/admin/overview", admin).data().get("bookingsToday").asInt()).isEqualTo(1);

        Res cancelled = post_("/admin/bookings/" + id + "/cancel", admin, body("reason", "Host reported the spot is flooded", "refund", "FULL"));
        assertThat(cancelled.data().get("refund").get("amount").asInt()).isEqualTo(485);
        assertThat(cancelled.data().get("refundStatus").asText()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT host_earnings_paise FROM bookings WHERE id = ?", Long.class, id)).isZero();
        assertThat(post_("/admin/bookings/" + id + "/cancel", admin, body("reason", "Again please", "refund", "FULL")).errorCode())
                .isEqualTo("CANCEL_NOT_ALLOWED");

        Res refunds = get_("/admin/refunds", admin);
        assertThat(refunds.data().get(0).get("method").asText()).isEqualTo("CARD");
        assertThat(refunds.body().get("meta").get("counts").get("PROCESSING").asInt()).isEqualTo(1);
        String refundId = refunds.data().get(0).get("id").asText();
        assertThat(post_("/admin/refunds/" + refundId + "/retry", admin, null).errorCode()).isEqualTo("NOT_FAILED");

        jdbc.update("UPDATE refunds SET status = 'FAILED', failure_reason = 'Bank declined' WHERE id = ?", refundId);
        assertThat(get_("/admin/overview", admin).data().get("refundsFailed").asInt()).isEqualTo(1);
        assertThat(post_("/admin/refunds/" + refundId + "/retry", admin, null).data().get("status").asText()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM refunds WHERE id = ?", Integer.class, refundId)).isEqualTo(2);
    }

    @Test
    void payouts() throws Exception {
        String spot = liveSpot(hostToken, "Sunrise Apts – Covered Bay", 12.9116, 77.6389, 1, "INSTANT");
        String driverId = userIdOf(loginWithPhone("+919845011100"));
        String hostId = userIdOf(hostToken);
        OffsetDateTime now = OffsetDateTime.now(IST);
        jdbc.update("""
                INSERT INTO bookings (id, booking_ref, listing_id, driver_id, host_id, booking_mode, status, start_at, end_at, vehicle_type,
                  vehicle_registration, parking_paise, service_fee_paise, gst_paise, total_paise, host_earnings_paise, confirmed_at,
                  checked_in_at, checked_out_at)
                VALUES ('bk_done1', 'PN-90002', ?, ?, ?, 'INSTANT', 'COMPLETED', ?, ?, 'SEDAN', 'KA01AB1234', 30000, 2000, 400, 32400, 30000, ?, ?, ?)
                """, spot, driverId, hostId, now.minusHours(3), now.minusHours(1), now.minusDays(1), now.minusHours(3), now.minusHours(1));
        String payoutId = payoutService.schedulePayouts().getFirst().getId();

        Res list = get_("/admin/payouts", admin);
        assertThat(list.data().get(0).get("amount").get("amount").asInt()).isEqualTo(300);
        assertThat(list.data().get(0).get("destination").asText()).startsWith("UPI · ");
        assertThat(list.body().get("meta").get("counts").get("SCHEDULED").asInt()).isEqualTo(1);
        assertThat(get_("/admin/overview", admin).data().get("payoutsScheduled").get("amount").get("amount").asInt()).isEqualTo(300);

        assertThat(post_("/admin/payouts/" + payoutId + "/retry", admin, null).errorCode()).isEqualTo("NOT_FAILED");
        jdbc.update("UPDATE payouts SET status = 'FAILED', failure_reason = 'Invalid UPI ID' WHERE id = ?", payoutId);
        assertThat(get_("/admin/overview", admin).data().get("payoutsFailed").asInt()).isEqualTo(1);
        assertThat(post_("/admin/payouts/" + payoutId + "/retry", admin, null).data().get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(get_("/admin/activity", admin).data().get(0).get("action").asText()).isEqualTo("PAYOUT_RETRIED");
    }
}
