package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingApiTest extends IntegrationTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private final OffsetDateTime start = OffsetDateTime.now(IST).plusDays(3).truncatedTo(ChronoUnit.HOURS);
    private String hostToken;
    private String spotId;
    private String driver;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = host("+919876543210");
        spotId = liveSpot(hostToken, "Sunrise Apts – Covered Bay", 12.9116, 77.6389, 1, "INSTANT");
        driver = loginWithPhone("+919845011100");
    }

    private Map<String, Object> booking(String registration, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Object> vehicle = new HashMap<>();
        vehicle.put("type", "SEDAN");
        vehicle.put("registrationNumber", registration);
        return Map.of("listingId", spotId, "quoteId", "qt_1", "vehicle", vehicle, "startAt", from.toString(), "endAt", to.toString(), "paymentMethod", "UPI");
    }

    private Res pay(String token, String bookingId, String orderId) throws Exception {
        return post_("/payments/verify", token, Map.of("bookingId", bookingId, "orderId", orderId, "paymentId", "pay_" + orderId,
                "signature", "mock_sig_" + orderId, "paymentMethod", "UPI"));
    }

    /** Books 63 hours (3 days: ₹450 + ₹30 fee + ₹5 GST = ₹485) and pays. Returns the booking id. */
    private String paidBooking(String token, String registration) throws Exception {
        Res hold = post_("/bookings", token, booking(registration, start, start.plusHours(63)));
        assertThat(hold.status()).isEqualTo(201);
        String id = hold.data().get("bookingId").asText();
        assertThat(pay(token, id, hold.data().get("payment").get("orderId").asText()).status()).isEqualTo(200);
        return id;
    }

    @Test
    void holdPayAndUseASpot() throws Exception {
        Res hold = post_("/bookings", driver, booking("KA 05 MN 1001", start, start.plusHours(63)));
        assertThat(hold.status()).isEqualTo(201);
        assertThat(hold.data().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(hold.data().get("bookingRef").asText()).matches("PN-\\d{5,}");
        assertThat(hold.data().get("amount").get("amount").asInt()).isEqualTo(485);
        assertThat(hold.data().get("holdExpiresAt").asText()).endsWith("+05:30");
        String id = hold.data().get("bookingId").asText();
        String orderId = hold.data().get("payment").get("orderId").asText();

        // The only bay is held, so nobody else can take these times, and hosts cannot book their own spot.
        String other = loginWithPhone("+919845011101");
        assertThat(post_("/bookings", other, booking("KA05MN1002", start.plusDays(1), start.plusDays(2))).errorCode()).isEqualTo("SPOT_UNAVAILABLE");
        assertThat(post_("/bookings", hostToken, booking("KA05MN1003", start.plusDays(10), start.plusDays(11))).errorCode()).isEqualTo("OWN_SPOT");

        // Entry details are hidden until payment.
        assertThat(get_("/bookings/" + id, driver).data().get("entryInstructions").isNull()).isTrue();
        assertThat(get_("/bookings/" + id, other).status()).isEqualTo(404);

        Res badSignature = post_("/payments/verify", driver, Map.of("bookingId", id, "orderId", orderId, "paymentId", "pay_x", "signature", "forged"));
        assertThat(badSignature.status()).isEqualTo(402);
        Res paid = pay(driver, id, orderId);
        assertThat(paid.data().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(paid.data().get("paidAmount").get("amount").asInt()).isEqualTo(485);
        assertThat(pay(driver, id, orderId).status()).as("verifying twice is fine").isEqualTo(200);

        Res detail = get_("/bookings/" + id, driver);
        assertThat(detail.data().get("entryInstructions").asText()).startsWith("Gate code");
        assertThat(detail.data().get("paymentMethod").asText()).isEqualTo("UPI");
        assertThat(detail.data().get("vehicle").get("registrationNumber").asText()).isEqualTo("KA05MN1001");
        assertThat(detail.data().get("durationDays").asInt()).isEqualTo(3);
        assertThat(detail.data().get("timeline").get(0).get("status").asText()).isEqualTo("CURRENT");

        Res upcoming = get_("/bookings?tab=upcoming", driver);
        assertThat(upcoming.data()).hasSize(1);
        assertThat(upcoming.body().get("meta").get("counts").get("upcoming").asInt()).isEqualTo(1);
        assertThat(upcoming.data().get(0).get("listing").get("thumbnailUrl").asText()).startsWith("/api/v1/files/");

        assertThat(post_("/bookings/" + id + "/check-in", driver, null).errorCode()).isEqualTo("CHECK_IN_TOO_EARLY");
        jdbc.update("UPDATE bookings SET start_at = now() - interval '1 hour', end_at = now() + interval '5 hours' WHERE id = ?", id);
        Res in = post_("/bookings/" + id + "/check-in", driver, null);
        assertThat(in.data().get("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(get_("/bookings?tab=current", driver).data()).hasSize(1);
        Res preview = get_("/bookings/" + id + "/cancellation", driver);
        assertThat(preview.data().get("canCancel").asBoolean()).isFalse();
        assertThat(post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "PLANS_CHANGED")).errorCode()).isEqualTo("CANCEL_NOT_ALLOWED");

        Res out = post_("/bookings/" + id + "/check-out", driver, null);
        assertThat(out.data().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(get_("/bookings?tab=past", driver).data().get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(post_("/bookings/" + id + "/check-out", driver, null).errorCode()).isEqualTo("INVALID_STATUS");
    }

    @Test
    void vehicleNumberIsNeededBeforePaying() throws Exception {
        Res hold = post_("/bookings", driver, booking(null, start, start.plusDays(1)));
        assertThat(hold.status()).isEqualTo(201);
        String id = hold.data().get("bookingId").asText();
        String orderId = hold.data().get("payment").get("orderId").asText();
        assertThat(pay(driver, id, orderId).errorCode()).isEqualTo("REGISTRATION_REQUIRED");

        assertThat(patch_("/bookings/" + id, driver, Map.of("vehicle", Map.of("type", "SEDAN", "registrationNumber", "12345"))).errorCode())
                .isEqualTo("INVALID_REGISTRATION");
        Res updated = patch_("/bookings/" + id, driver, Map.of("vehicle", Map.of("type", "SEDAN", "registrationNumber", "ka05mn1004")));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().get("payment").get("orderId").asText()).as("same order is reused").isEqualTo(orderId);
        assertThat(pay(driver, id, orderId).data().get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void unpaidHoldsEnd() throws Exception {
        Res hold = post_("/bookings", driver, booking("KA05MN1005", start, start.plusDays(1)));
        String id = hold.data().get("bookingId").asText();
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", id);

        assertThat(pay(driver, id, hold.data().get("payment").get("orderId").asText()).errorCode()).isEqualTo("HOLD_EXPIRED");
        assertThat(get_("/bookings/" + id, driver).data().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(get_("/bookings?tab=past", driver).data()).hasSize(1);

        // The bay is free again.
        String other = loginWithPhone("+919845011101");
        assertThat(post_("/bookings", other, booking("KA05MN1006", start, start.plusDays(1))).status()).isEqualTo(201);
    }

    @Test
    void cancellingEarlyRefundsEverything() throws Exception {
        String id = paidBooking(driver, "KA05MN1007");
        Res preview = get_("/bookings/" + id + "/cancellation", driver);
        assertThat(preview.data().get("isFree").asBoolean()).isTrue();
        assertThat(preview.data().get("refund").get("amount").asInt()).isEqualTo(485);

        assertThat(post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "NOT_A_REASON")).status()).isEqualTo(422);
        Res cancelled = post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "PLANS_CHANGED"));
        assertThat(cancelled.data().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.data().get("refund").get("amount").asInt()).isEqualTo(485);
        assertThat(cancelled.data().get("refundStatus").asText()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT amount_paise FROM refunds WHERE booking_id = ?", Long.class, id)).isEqualTo(48500);
        assertThat(jdbc.queryForObject("SELECT host_earnings_paise FROM bookings WHERE id = ?", Long.class, id)).isZero();
        assertThat(get_("/bookings/" + id, driver).data().get("cancellation").get("refund").get("amount").asInt()).isEqualTo(485);
        assertThat(post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "PLANS_CHANGED")).errorCode()).isEqualTo("CANCEL_NOT_ALLOWED");
    }

    @Test
    void cancellingLateKeepsTheFirstDayAndFee() throws Exception {
        String id = paidBooking(driver, "KA05MN1008");
        jdbc.update("UPDATE bookings SET start_at = now() + interval '2 hours', end_at = now() + interval '65 hours' WHERE id = ?", id);

        Res preview = get_("/bookings/" + id + "/cancellation", driver);
        assertThat(preview.data().get("isFree").asBoolean()).isFalse();
        assertThat(preview.data().get("nonRefundable").get("amount").asInt()).isEqualTo(150 + 30 + 5);
        assertThat(preview.data().get("refund").get("amount").asInt()).isEqualTo(300);
        assertThat(preview.data().get("refundTo").asText()).isEqualTo("UPI");

        Res cancelled = post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "FOUND_OTHER_SPOT", "note", "Closer to work"));
        assertThat(cancelled.data().get("refund").get("amount").asInt()).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT host_earnings_paise FROM bookings WHERE id = ?", Long.class, id)).isEqualTo(15000);
        assertThat(get_("/bookings/" + id, driver).data().get("cancellation").get("refund").get("amount").asInt()).isEqualTo(300);
    }

    @Test
    void cancellingAnUnpaidHoldRefundsNothing() throws Exception {
        String id = post_("/bookings", driver, booking("KA05MN1009", start, start.plusDays(1))).data().get("bookingId").asText();
        Res cancelled = post_("/bookings/" + id + "/cancel", driver, Map.of("reason", "BOOKED_BY_MISTAKE"));
        assertThat(cancelled.data().get("refund").get("amount").asInt()).isZero();
        assertThat(cancelled.data().get("refundStatus").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds", Integer.class)).isZero();
    }

    @Test
    void bookingNeedsALiveSpotAndValidTimes() throws Exception {
        assertThat(post_("/bookings", driver, booking("KA05MN1010", start.minusDays(5), start)).errorCode()).isEqualTo("START_IN_PAST");
        jdbc.update("UPDATE listings SET status = 'PAUSED' WHERE id = ?", spotId);
        assertThat(post_("/bookings", driver, booking("KA05MN1010", start, start.plusDays(1))).status()).isEqualTo(404);
        assertThat(get_("/bookings?tab=nope", driver).status()).isEqualTo(422);
        assertThat(get_("/bookings", null).status()).isEqualTo(401);
    }
}
