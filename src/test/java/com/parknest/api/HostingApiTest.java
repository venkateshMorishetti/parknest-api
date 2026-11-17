package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostingApiTest extends IntegrationTest {

    @Test
    void startHostingWithAPayoutAccount() throws Exception {
        String token = loginWithPhone("+919876543210");
        Res before = get_("/host/onboarding", token);
        assertThat(before.data().get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(post_("/host/listings", token, Map.of()).errorCode()).isEqualTo("HOSTING_NOT_STARTED");

        Res noTerms = post_("/host/onboarding", token, Map.of("payout", Map.of("method", "UPI", "upiId", "venkatesh@okaxis")));
        assertThat(noTerms.body().get("errors").get(0).get("field").asText()).isEqualTo("acceptedHostTerms");
        Res badUpi = post_("/host/onboarding", token, Map.of("payout", Map.of("method", "UPI", "upiId", "nope"), "acceptedHostTerms", true));
        assertThat(badUpi.body().get("errors").get(0).get("field").asText()).isEqualTo("payout.upiId");

        Res on = post_("/host/onboarding", token, Map.of("payout",
                Map.of("method", "BANK_ACCOUNT", "accountHolderName", "Venkatesh Kumar", "accountNumber", "50100234567890", "ifsc", "hdfc0001234"),
                "acceptedHostTerms", true));
        assertThat(on.status()).isEqualTo(201);
        assertThat(on.data().get("canHost").asBoolean()).isTrue();
        assertThat(on.data().get("payoutAccount").get("display").asText()).isEqualTo("HDFC ••••7890");
        assertThat(on.data().get("payoutAccount").get("status").asText()).isEqualTo("VERIFYING");
        // The account number is stored encrypted, never in clear.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_accounts WHERE convert_from(account_number_encrypted, 'LATIN1') LIKE '%50100234567890%'", Integer.class)).isZero();
        assertThat(get_("/users/me", token).data().get("hostProfile").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(post_("/host/onboarding", token, Map.of("payout", Map.of("method", "UPI", "upiId", "a@ybl"), "acceptedHostTerms", true)).errorCode())
                .isEqualTo("ALREADY_HOSTING");
    }

    @Test
    void settingsPayoutChangeAndNotifications() throws Exception {
        String token = host("+919876543210");
        Res settings = get_("/host/settings", token);
        assertThat(settings.data().get("payoutAccount").get("display").asText()).isEqualTo("ve••••sh@okaxis");
        assertThat(settings.data().get("notifications").get("newRequests").get("sms").asBoolean()).isTrue();

        Res changed = put_("/host/payout-account", token, Map.of("method", "UPI", "upiId", "venkat.k@ybl"));
        assertThat(changed.data().get("display").asText()).isEqualTo("ve••••.k@ybl");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_accounts", Integer.class)).as("old account kept").isEqualTo(2);

        Map<String, Object> off = Map.of("sms", false, "email", false);
        Res saved = put_("/host/settings/notifications", token, Map.of("newRequests", off, "bookingUpdates", off, "payouts", off, "tips", off));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(get_("/host/settings", token).data().get("notifications").get("newRequests").get("sms").asBoolean()).isFalse();
    }

    @Test
    void addListAndEditSpots() throws Exception {
        String token = host("+919876543210");
        Res created = post_("/host/listings", token, spot(token, "Sunrise Apts – Covered Bay", 12.9116, 77.6389, 3, "INSTANT"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().get("status").asText()).isEqualTo("NEEDS_APPROVAL");
        String id = created.data().get("id").asText();

        Res list = get_("/host/listings", token);
        assertThat(list.data()).hasSize(1);
        assertThat(list.data().get(0).get("bays").asInt()).isEqualTo(3);
        assertThat(list.data().get(0).get("thumbnailUrl").asText()).startsWith("/api/v1/files/file_");
        assertThat(list.body().get("meta").get("counts").get("NEEDS_APPROVAL").asInt()).isEqualTo(1);

        Res editable = get_("/host/listings/" + id, token);
        assertThat(editable.data().get("entryInstructions").asText()).contains("Gate code");

        // Not bookable yet: hidden from the public.
        assertThat(get_("/listings/" + id, null).status()).isEqualTo(404);
        assertThat(patch_("/host/listings/" + id + "/status", token, Map.of("status", "PAUSED")).errorCode()).isEqualTo("INVALID_STATUS");

        // Approved (admin), then a small edit keeps it live, but moving the pin sends it back to review.
        jdbc.update("UPDATE listings SET status = 'ACTIVE' WHERE id = ?", id);
        Map<String, Object> edit = new HashMap<>(spot(token, "Sunrise Apts – Bay B-12", 12.9116, 77.6389, 3, "INSTANT"));
        Res small = put_("/host/listings/" + id, token, edit);
        assertThat(small.data().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(small.data().get("reviewEstimate").isNull()).isTrue();

        Map<String, Object> moved = new HashMap<>(spot(token, "Sunrise Apts – Bay B-12", 12.9200, 77.6389, 3, "INSTANT"));
        Res review = put_("/host/listings/" + id, token, moved);
        assertThat(review.data().get("status").asText()).isEqualTo("NEEDS_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT review_reason FROM listings WHERE id = ?", String.class, id)).isEqualTo("LOCATION_CHANGED");
    }

    @Test
    void pauseAndResume() throws Exception {
        String token = host("+919876543210");
        String id = liveSpot(token, "Villa Driveway", 12.9121, 77.6412, 1, "HOST_APPROVAL");
        assertThat(patch_("/host/listings/" + id + "/status", token, Map.of("status", "PAUSED")).data().get("status").asText()).isEqualTo("PAUSED");
        assertThat(get_("/listings/" + id, null).status()).isEqualTo(404);
        assertThat(patch_("/host/listings/" + id + "/status", token, Map.of("status", "ACTIVE")).data().get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsBadSpotsAndOtherPeoplesPhotos() throws Exception {
        String token = host("+919876543210");
        Map<String, Object> req = new HashMap<>(spot(token, "Spot", 12.9116, 77.6389, 1, "INSTANT"));
        req.put("pricing", Map.of("currency", "INR", "perDay", 100, "perHour", 150));
        assertThat(post_("/host/listings", token, req).body().get("errors").get(0).get("field").asText()).isEqualTo("pricing.perHour");
        req = new HashMap<>(spot(token, "Spot", 51.5, -0.12, 1, "INSTANT"));
        assertThat(post_("/host/listings", token, req).body().get("errors").get(0).get("field").asText()).isEqualTo("address.geo");
        req = new HashMap<>(spot(token, "Spot", 12.9116, 77.6389, 60, "INSTANT"));
        assertThat(post_("/host/listings", token, req).body().get("errors").get(0).get("field").asText()).isEqualTo("bays");

        String other = host("+919845011100");
        String theirPhoto = uploadPhoto(other);
        req = new HashMap<>(spot(token, "Spot", 12.9116, 77.6389, 1, "INSTANT"));
        req.put("photoFileIds", List.of(theirPhoto));
        assertThat(post_("/host/listings", token, req).body().get("errors").get(0).get("field").asText()).isEqualTo("photoFileIds");
        // Someone else's spot does not exist for this host.
        String theirs = liveSpot(other, "Their spot", 12.9116, 77.6389, 1, "INSTANT");
        assertThat(get_("/host/listings/" + theirs, token).status()).isEqualTo(404);
    }

    @Test
    void uploadsOnlyAcceptRealImages() throws Exception {
        String token = loginWithPhone("+919876543210");
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "evil.jpg", "image/jpeg", "<script>".getBytes()))
                .param("purpose", "LISTING_PHOTO");
        assertThat(call(request, token, null).errorCode()).isEqualTo("UNSUPPORTED_TYPE");
        String id = uploadPhoto(token);
        var file = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/files/" + id)).andReturn().getResponse();
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).isEqualTo("image/jpeg");
    }
}
