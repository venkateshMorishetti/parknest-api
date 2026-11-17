package com.parknest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Base for API tests: a real database (emptied before each test) and helpers to call the API and sign in. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("""
                TRUNCATE admin_actions, payout_items, payouts, refunds, payments, bookings, listing_photos, listings, files,
                         payout_accounts, host_profiles, notification_preferences, vehicles, sessions, otp_requests,
                         auth_identities, users CASCADE
                """);
    }

    /** Status code and parsed envelope. */
    protected record Res(int status, JsonNode body) {
        public JsonNode data() {
            return body.get("data");
        }

        public String errorCode() {
            return body.get("errors").get(0).get("code").asText();
        }
    }

    protected Res call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MvcResult result = mvc.perform(request).andReturn();
        String text = result.getResponse().getContentAsString();
        return new Res(result.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    protected Res get_(String path, String token) throws Exception {
        return call(get("/api/v1" + path), token, null);
    }

    protected Res post_(String path, String token, Object body) throws Exception {
        return call(post("/api/v1" + path), token, body);
    }

    /** Signs in with phone OTP (the test code is always 123456) and returns the access token. */
    protected String loginWithPhone(String phone) throws Exception {
        Res sent = post_("/auth/otp/send", null, java.util.Map.of("phone", phone));
        String id = sent.data().get("otpRequestId").asText();
        Res session = post_("/auth/otp/verify", null, java.util.Map.of("otpRequestId", id, "phone", phone, "otp", "123456"));
        return session.data().get("accessToken").asText();
    }

    protected Res put_(String path, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1" + path), token, body);
    }

    protected Res patch_(String path, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1" + path), token, body);
    }

    /** A tiny valid JPEG (only the first bytes are checked). */
    protected static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    protected String uploadPhoto(String token) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "bay.jpg", "image/jpeg", JPEG))
                .param("purpose", "LISTING_PHOTO");
        return call(request, token, null).data().get("fileId").asText();
    }

    /** Signs in, turns hosting on with a UPI payout account, and returns the token. */
    protected String host(String phone) throws Exception {
        String token = loginWithPhone(phone);
        post_("/host/onboarding", token, java.util.Map.of("payout", java.util.Map.of("method", "UPI", "upiId", "venkatesh@okaxis"), "acceptedHostTerms", true));
        return token;
    }

    /** A valid add-spot request (HSR Layout), with one new photo. */
    protected java.util.Map<String, Object> spot(String token, String title, double lat, double lng, int bays, String mode) throws Exception {
        java.util.Map<String, Object> req = new java.util.HashMap<>();
        req.put("title", title);
        req.put("address", java.util.Map.of("line1", "12, 6th Main", "area", "HSR Layout", "city", "Bengaluru", "state", "Karnataka", "pincode", "560102",
                "geo", java.util.Map.of("lat", lat, "lng", lng)));
        req.put("bayLabel", "Bay B-12");
        req.put("entryInstructions", "Gate code 4821. Left ramp to bay B-12.");
        req.put("spotType", "COVERED");
        req.put("bays", bays);
        req.put("vehicleTypes", java.util.List.of("HATCHBACK", "SEDAN"));
        req.put("security", java.util.List.of("CCTV", "GATED"));
        req.put("pricing", java.util.Map.of("currency", "INR", "perDay", 150, "perHour", 25));
        req.put("accessHours", java.util.Map.of("is24x7", true));
        req.put("bookingMode", mode);
        req.put("photoFileIds", java.util.List.of(uploadPhoto(token)));
        return req;
    }

    /** Adds a spot and approves it directly in the database (until the admin API exists). Returns its id. */
    protected String liveSpot(String token, String title, double lat, double lng, int bays, String mode) throws Exception {
        String id = post_("/host/listings", token, spot(token, title, lat, lng, bays, mode)).data().get("id").asText();
        jdbc.update("UPDATE listings SET status = 'ACTIVE', review_reason = NULL WHERE id = ?", id);
        return id;
    }

    protected String userIdOf(String token) throws Exception {
        return get_("/users/me", token).data().get("id").asText();
    }
}
