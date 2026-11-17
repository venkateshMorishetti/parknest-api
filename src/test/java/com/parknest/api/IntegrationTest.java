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

    protected String userIdOf(String token) throws Exception {
        return get_("/users/me", token).data().get("id").asText();
    }
}
