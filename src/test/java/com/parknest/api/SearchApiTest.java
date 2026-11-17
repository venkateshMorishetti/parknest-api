package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchApiTest extends IntegrationTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private final OffsetDateTime start = OffsetDateTime.now(IST).plusDays(2).truncatedTo(ChronoUnit.HOURS);
    private String near;
    private String basement;
    private String far;

    @BeforeEach
    void spots() throws Exception {
        String token = host("+919876543210");
        near = liveSpot(token, "Sunrise Apts – Covered Bay", 12.9116, 77.6389, 1, "INSTANT");
        basement = liveSpot(token, "Orchid Basement", 12.9081, 77.6476, 2, "HOST_APPROVAL");
        jdbc.update("UPDATE listings SET spot_type = 'BASEMENT', price_per_day_paise = 18000 WHERE id = ?", basement);
        far = liveSpot(token, "Whitefield Garage", 12.9698, 77.7500, 1, "INSTANT");
    }

    private Map<String, Object> search(Map<String, Object> changes) {
        Map<String, Object> req = new HashMap<>();
        req.put("location", Map.of("label", "HSR Layout, Bengaluru", "geo", Map.of("lat", 12.9116, "lng", 77.6389)));
        req.put("vehicleType", "SEDAN");
        req.put("spotType", "ANY");
        req.put("startAt", start.toString());
        req.put("endAt", start.plusDays(3).toString());
        req.put("filters", Map.of("instantBookOnly", false, "coveredOnly", false, "cctv", false));
        req.put("sort", "NEAREST");
        req.put("page", 1);
        req.put("pageSize", 20);
        req.putAll(changes);
        return req;
    }

    @Test
    void findsNearbySpotsNearestFirst() throws Exception {
        Res res = post_("/listings/search", null, search(Map.of()));
        assertThat(res.status()).isEqualTo(200);
        JsonNode items = res.data();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asText()).isEqualTo(near);
        assertThat(items.get(0).get("distanceKm").asDouble()).isEqualTo(0.0);
        assertThat(items.get(1).get("pricing").get("perDay").asInt()).isEqualTo(180);
        assertThat(res.body().get("meta").get("pagination").get("totalItems").asInt()).isEqualTo(2);
        assertThat(res.body().get("meta").get("mapBounds").get("north").asDouble()).isEqualTo(12.9116);
    }

    @Test
    void areaSearchFiltersAndSorting() throws Exception {
        Res area = post_("/listings/search", null, search(Map.of("area", Map.of("north", 13.0, "south", 12.95, "east", 77.8, "west", 77.7))));
        assertThat(area.data()).hasSize(1);
        assertThat(area.data().get(0).get("id").asText()).isEqualTo(far);

        Res instant = post_("/listings/search", null, search(Map.of("filters", Map.of("instantBookOnly", true, "coveredOnly", false, "cctv", false))));
        assertThat(instant.data()).hasSize(1);
        Res cheap = post_("/listings/search", null, search(Map.of("filters", Map.of("instantBookOnly", false, "coveredOnly", false, "cctv", false, "maxPricePerDay", 160))));
        assertThat(cheap.data()).hasSize(1);
        Res priceHigh = post_("/listings/search", null, search(Map.of("sort", "PRICE_HIGH")));
        assertThat(priceHigh.data().get(0).get("id").asText()).isEqualTo(basement);
        Res bikes = post_("/listings/search", null, search(Map.of("vehicleType", "BIKE")));
        assertThat(bikes.data()).isEmpty();
        Res byName = post_("/listings/search", null, search(Map.of("location", Map.of("label", "HSR Layout"))));
        assertThat(byName.data()).hasSize(2);
        assertThat(post_("/listings/search", null, search(Map.of("location", Map.of("label", "Atlantis")))).errorCode()).isEqualTo("LOCATION_NOT_FOUND");
    }

    @Test
    void fullyBookedSpotsAreLeftOut() throws Exception {
        String driver = loginWithPhone("+919845011100");
        String driverId = userIdOf(driver);
        String hostId = jdbc.queryForObject("SELECT host_id FROM listings WHERE id = ?", String.class, near);
        jdbc.update("""
                INSERT INTO bookings (id, booking_ref, listing_id, driver_id, host_id, booking_mode, status, start_at, end_at, vehicle_type,
                  vehicle_registration, parking_paise, service_fee_paise, gst_paise, total_paise, host_earnings_paise, confirmed_at)
                VALUES ('bk_test1', 'PN-10001', ?, ?, ?, 'INSTANT', 'CONFIRMED', ?, ?, 'SEDAN', 'KA01AB1234', 45000, 3000, 500, 48500, 45000, now())
                """, near, driverId, hostId, start.plusDays(1), start.plusDays(2));
        Res res = post_("/listings/search", null, search(Map.of()));
        assertThat(res.data()).hasSize(1);
        assertThat(res.data().get(0).get("id").asText()).isEqualTo(basement);
        // Before that booking starts, it is free again.
        Res earlier = post_("/listings/search", null, search(Map.of("endAt", start.plusDays(1).toString())));
        assertThat(earlier.data()).hasSize(2);
    }

    @Test
    void spotPageAndQuote() throws Exception {
        Res detail = get_("/listings/" + near + "?lat=12.9121&lng=77.6446", null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().get("distanceKm").asDouble()).isEqualTo(0.6);
        assertThat(detail.data().has("entryInstructions")).as("entry details only after booking").isFalse();
        assertThat(detail.data().get("photos").get(0).get("url").asText()).startsWith("/api/v1/files/");

        Res quote = post_("/listings/" + near + "/quote", null, Map.of("startAt", start.toString(), "endAt", start.plusHours(63).toString(), "vehicleType", "SEDAN"));
        assertThat(quote.data().get("total").asInt()).isEqualTo(485);
        assertThat(quote.data().get("lineItems").get(0).get("label").asText()).isEqualTo("₹150 × 3 days");
        Res hours = post_("/listings/" + near + "/quote", null, Map.of("startAt", start.toString(), "endAt", start.plusHours(3).toString(), "vehicleType", "SEDAN"));
        assertThat(hours.data().get("lineItems").get(0).get("label").asText()).isEqualTo("₹25 × 3 hours");
        assertThat(hours.data().get("total").asInt()).isEqualTo(75 + 10 + 2);

        assertThat(post_("/listings/" + near + "/quote", null, Map.of("startAt", start.toString(), "endAt", start.plusDays(1).toString(), "vehicleType", "BIKE")).errorCode())
                .isEqualTo("VEHICLE_NOT_SUPPORTED");
        assertThat(post_("/listings/" + near + "/quote", null, Map.of("startAt", start.minusDays(5).toString(), "endAt", start.toString(), "vehicleType", "SEDAN")).errorCode())
                .isEqualTo("START_IN_PAST");
    }

    @Test
    void placesAndContent() throws Exception {
        assertThat(get_("/locations/suggest?q=kora", null).data().get(0).get("area").asText()).isEqualTo("Koramangala");
        assertThat(get_("/locations/reverse?lat=12.9121&lng=77.6446", null).data().get("area").asText()).isEqualTo("HSR Layout");
        assertThat(get_("/locations/reverse?lat=28.61&lng=77.20", null).errorCode()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(get_("/content/home", null).status()).isEqualTo(200);
        assertThat(get_("/meta/search-options", null).data().get("vehicleTypes")).hasSize(4);
    }
}
