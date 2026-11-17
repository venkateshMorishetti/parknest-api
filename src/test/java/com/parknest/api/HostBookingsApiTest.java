package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.parknest.api.bookings.PayoutService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HostBookingsApiTest extends IntegrationTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private final OffsetDateTime start = OffsetDateTime.now(IST).plusDays(3).truncatedTo(ChronoUnit.HOURS);

    @Autowired PayoutService payoutService;

    private String hostToken;
    private String spotId;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = host("+919876543210");
        jdbc.update("UPDATE users SET name = 'Venkatesh Morishetti'");
        spotId = liveSpot(hostToken, "Villa Driveway, 6th Main", 12.9116, 77.6389, 1, "HOST_APPROVAL");
    }

    private String request(String token, String registration, OffsetDateTime from, OffsetDateTime to) throws Exception {
        Res res = post_("/bookings", token, Map.of("listingId", spotId, "vehicle", Map.of("type", "HATCHBACK", "registrationNumber", registration),
                "startAt", from.toString(), "endAt", to.toString()));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.data().get("status").asText()).isEqualTo("PENDING_HOST_APPROVAL");
        assertThat(res.data().get("payment").isNull()).as("no payment until the host approves").isTrue();
        return res.data().get("bookingId").asText();
    }

    private Res decide(String id, String decision, String reason) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("decision", decision);
        body.put("reason", reason);
        return post_("/host/booking-requests/" + id + "/decision", hostToken, body);
    }

    @Test
    void approvingARequestDeclinesTheOnesThatNoLongerFit() throws Exception {
        String arun = loginWithPhone("+919845011100");
        put_("/users/me", arun, Map.of("name", "Arun Kumar"));
        String meera = loginWithPhone("+919845011101");
        String priya = loginWithPhone("+919845011102");
        String a = request(arun, "KA05MN2001", start, start.plusDays(3));
        String m = request(meera, "KA05MN2002", start.plusDays(1), start.plusDays(2));
        String p = request(priya, "KA05MN2003", start.plusDays(10), start.plusDays(11));

        JsonNode dashboard = get_("/host/dashboard", hostToken).data();
        assertThat(dashboard.get("bookingRequests")).hasSize(3);
        JsonNode first = dashboard.get("bookingRequests").get(0);
        assertThat(first.get("id").asText()).isEqualTo(a);
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("guest").get("name").asText()).isEqualTo("Arun K");
        assertThat(first.get("overlappingRequestIds").get(0).asText()).isEqualTo(m);
        assertThat(dashboard.get("listings").get(0).get("pricePerDay").asInt()).isEqualTo(150);

        assertThat(decide(a, "MAYBE", null).errorCode()).isEqualTo("INVALID_DECISION");
        assertThat(post_("/host/booking-requests/" + a + "/decision", arun, Map.of("decision", "APPROVE")).errorCode())
                .as("drivers are not hosts").isEqualTo("HOSTING_NOT_STARTED");

        Res approved = decide(a, "APPROVE", null);
        assertThat(approved.data().get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.data().get("bookingId").asText()).isEqualTo(a);
        assertThat(approved.data().get("autoDeclinedRequestIds")).hasSize(1);
        assertThat(approved.data().get("autoDeclinedRequestIds").get(0).asText()).isEqualTo(m);
        assertThat(decide(m, "APPROVE", null).errorCode()).isEqualTo("ALREADY_DECIDED");
        assertThat(decide(p, "DECLINE", "Spot not available on these dates").data().get("status").asText()).isEqualTo("DECLINED");
        assertThat(get_("/host/dashboard", hostToken).data().get("bookingRequests")).isEmpty();
        assertThat(get_("/bookings/" + m, meera).data().get("status").asText()).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT declined_by_booking_id FROM bookings WHERE id = ?", String.class, m)).isEqualTo(a);

        // Arun now pays: the PATCH returns the payment order.
        Res toPay = patch_("/bookings/" + a, arun, Map.of("vehicle", Map.of("type", "HATCHBACK", "registrationNumber", "KA05MN2001")));
        assertThat(toPay.data().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        String orderId = toPay.data().get("payment").get("orderId").asText();
        Res paid = post_("/payments/verify", arun, Map.of("bookingId", a, "orderId", orderId, "paymentId", "pay_1", "signature", "mock_sig_" + orderId));
        assertThat(paid.data().get("status").asText()).isEqualTo("CONFIRMED");

        Res upcoming = get_("/host/bookings?tab=upcoming", hostToken);
        assertThat(upcoming.data()).hasSize(1);
        assertThat(upcoming.data().get(0).get("hostEarnings").get("amount").asInt()).isEqualTo(450);
        assertThat(upcoming.data().get(0).get("driver").get("name").asText()).isEqualTo("Arun K");
        assertThat(upcoming.body().get("meta").get("counts").get("past").asInt()).isZero();
        assertThat(get_("/host/dashboard", hostToken).data().get("summary").get("upcomingBookings").asInt()).isEqualTo(1);
    }

    @Test
    void approvingNeedsAFreeBay() throws Exception {
        String arun = loginWithPhone("+919845011100");
        String a = request(arun, "KA05MN2001", start, start.plusDays(1));
        // Someone else's paid booking took the bay meanwhile (e.g. the spot was instant for a while).
        String other = userIdOf(loginWithPhone("+919845011101"));
        String hostId = userIdOf(hostToken);
        jdbc.update("""
                INSERT INTO bookings (id, booking_ref, listing_id, driver_id, host_id, booking_mode, status, start_at, end_at, vehicle_type,
                  vehicle_registration, parking_paise, service_fee_paise, gst_paise, total_paise, host_earnings_paise, confirmed_at)
                VALUES ('bk_test1', 'PN-90001', ?, ?, ?, 'INSTANT', 'CONFIRMED', ?, ?, 'SEDAN', 'KA01AB1234', 15000, 1000, 200, 16200, 15000, now())
                """, spotId, other, hostId, start, start.plusDays(1));
        assertThat(decide(a, "APPROVE", null).errorCode()).isEqualTo("SPOT_UNAVAILABLE");
    }

    @Test
    void earningsAndPayouts() throws Exception {
        String driverId = userIdOf(loginWithPhone("+919845011100"));
        String hostId = userIdOf(hostToken);
        OffsetDateTime now = OffsetDateTime.now(IST);
        jdbc.update("""
                INSERT INTO bookings (id, booking_ref, listing_id, driver_id, host_id, booking_mode, status, start_at, end_at, vehicle_type,
                  vehicle_registration, parking_paise, service_fee_paise, gst_paise, total_paise, host_earnings_paise, confirmed_at,
                  checked_in_at, checked_out_at)
                VALUES ('bk_done1', 'PN-90002', ?, ?, ?, 'INSTANT', 'COMPLETED', ?, ?, 'SEDAN', 'KA01AB1234', 30000, 2000, 400, 32400, 30000, ?, ?, ?)
                """, spotId, driverId, hostId, now.minusHours(3), now.minusHours(1), now.minusDays(1), now.minusHours(3), now.minusHours(1));

        JsonNode before = get_("/host/earnings", hostToken).data();
        assertThat(before.get("summary").get("thisMonth").get("amount").asInt()).isEqualTo(300);
        assertThat(before.get("summary").get("pendingPayout").get("amount").asInt()).isEqualTo(300);
        assertThat(before.get("summary").get("nextPayoutDate").isNull()).isFalse();
        assertThat(before.get("monthly")).hasSize(6);
        assertThat(before.get("monthly").get(5).get("amount").asInt()).isEqualTo(300);
        assertThat(before.get("payouts")).isEmpty();
        assertThat(get_("/host/bookings?tab=past", hostToken).data()).hasSize(1);

        assertThat(payoutService.schedulePayouts()).hasSize(1);
        assertThat(payoutService.schedulePayouts()).as("a booking is paid out once").isEmpty();

        JsonNode after = get_("/host/earnings", hostToken).data();
        assertThat(after.get("summary").get("pendingPayout").get("amount").asInt()).isEqualTo(300);
        JsonNode payout = after.get("payouts").get(0);
        assertThat(payout.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(payout.get("amount").get("amount").asInt()).isEqualTo(300);
        assertThat(payout.get("bookingCount").asInt()).isEqualTo(1);
        assertThat(payout.get("destination").asText()).isEqualTo("UPI · ve••••sh@okaxis");

        jdbc.update("UPDATE payouts SET status = 'PAID', paid_at = now()");
        JsonNode paid = get_("/host/earnings", hostToken).data();
        assertThat(paid.get("summary").get("pendingPayout").get("amount").asInt()).isZero();
        assertThat(paid.get("summary").get("paidThisYear").get("amount").asInt()).isEqualTo(300);

        assertThat(get_("/host/dashboard", hostToken).data().get("summary").get("earningsThisMonth").get("amount").asInt()).isEqualTo(300);
        assertThat(get_("/host/earnings", loginWithPhone("+919845011101")).errorCode()).isEqualTo("HOSTING_NOT_STARTED");
    }
}
