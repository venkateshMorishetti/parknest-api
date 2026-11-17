package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthApiTest extends IntegrationTest {

    private static final String PHONE = "+919876543210";

    @Test
    void phoneLoginCreatesAVerifiedAccountOnce() throws Exception {
        Res sent = post_("/auth/otp/send", null, Map.of("phone", PHONE));
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.data().get("maskedPhone").asText()).isEqualTo("+91 98••• ••210");

        Res session = post_("/auth/otp/verify", null, Map.of("otpRequestId", sent.data().get("otpRequestId").asText(), "phone", PHONE, "otp", "123456"));
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.data().get("isNewUser").asBoolean()).isTrue();
        assertThat(session.data().get("user").get("isPhoneVerified").asBoolean()).isTrue();

        String again = loginWithPhone(PHONE);
        assertThat(userIdOf(again)).isEqualTo(session.data().get("user").get("id").asText());
    }

    @Test
    void wrongCodesCountAndThenLockTheCode() throws Exception {
        String id = post_("/auth/otp/send", null, Map.of("phone", PHONE)).data().get("otpRequestId").asText();
        for (int i = 0; i < 5; i++) {
            Res wrong = post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", PHONE, "otp", "000000"));
            assertThat(wrong.status()).isEqualTo(400);
            assertThat(wrong.errorCode()).isEqualTo("OTP_INVALID");
        }
        Res locked = post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", PHONE, "otp", "123456"));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.errorCode()).isEqualTo("OTP_LOCKED");
    }

    @Test
    void codeWorksOnlyOnceAndOnlyForItsNumber() throws Exception {
        String id = post_("/auth/otp/send", null, Map.of("phone", PHONE)).data().get("otpRequestId").asText();
        assertThat(post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", "+919812345678", "otp", "123456")).errorCode()).isEqualTo("OTP_EXPIRED");
        assertThat(post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", PHONE, "otp", "123456")).status()).isEqualTo(200);
        assertThat(post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", PHONE, "otp", "123456")).errorCode()).isEqualTo("OTP_EXPIRED");
    }

    @Test
    void invalidPhoneIsRejected() throws Exception {
        Res res = post_("/auth/otp/send", null, Map.of("phone", "12345"));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.body().get("errors").get(0).get("field").asText()).isEqualTo("phone");
    }

    @Test
    void googleUserMustAddAMobileNumberBeforeUsingTheApp() throws Exception {
        Res google = post_("/auth/google", null, Map.of("idToken", "mock-google-id-token"));
        assertThat(google.status()).isEqualTo(200);
        String token = google.data().get("accessToken").asText();
        assertThat(google.data().get("user").get("phone").isNull()).isTrue();

        // Allowed: reading the profile. Blocked: anything else.
        assertThat(get_("/users/me", token).status()).isEqualTo(200);
        Res blocked = post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1234", "type", "SEDAN"));
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.errorCode()).isEqualTo("PHONE_NOT_VERIFIED");

        String id = post_("/users/me/phone/otp", token, Map.of("phone", PHONE)).data().get("otpRequestId").asText();
        Res linked = post_("/users/me/phone/verify", token, Map.of("otpRequestId", id, "phone", PHONE, "otp", "123456"));
        assertThat(linked.status()).isEqualTo(200);
        assertThat(linked.data().get("isPhoneVerified").asBoolean()).isTrue();
        assertThat(linked.data().get("authProviders").toString()).contains("GOOGLE").contains("PHONE");

        assertThat(post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1234", "type", "SEDAN")).status()).isEqualTo(201);
        // Signing in with that phone now opens the same account.
        assertThat(userIdOf(loginWithPhone(PHONE))).isEqualTo(linked.data().get("id").asText());
    }

    @Test
    void aNumberCannotBelongToTwoAccounts() throws Exception {
        loginWithPhone(PHONE);
        String googleToken = post_("/auth/google", null, Map.of("idToken", "mock-google-id-token")).data().get("accessToken").asText();
        Res taken = post_("/users/me/phone/otp", googleToken, Map.of("phone", PHONE));
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.errorCode()).isEqualTo("PHONE_IN_USE");
    }

    @Test
    void googleSignInTakesAnUnverifiedEmailFromAnotherAccount() throws Exception {
        String phoneToken = loginWithPhone(PHONE);
        call(put("/api/v1/users/me"), phoneToken, Map.of("name", "Someone", "email", "venkatesh@gmail.com"));
        Res google = post_("/auth/google", null, Map.of("idToken", "mock-google-id-token"));
        assertThat(google.status()).isEqualTo(200);
        assertThat(google.data().get("user").get("email").asText()).isEqualTo("venkatesh@gmail.com");
        assertThat(get_("/users/me", phoneToken).data().get("email").isNull()).isTrue();
    }

    @Test
    void refreshTokensWorkOnce() throws Exception {
        String id = post_("/auth/otp/send", null, Map.of("phone", PHONE)).data().get("otpRequestId").asText();
        String refresh = post_("/auth/otp/verify", null, Map.of("otpRequestId", id, "phone", PHONE, "otp", "123456")).data().get("refreshToken").asText();
        Res first = post_("/auth/refresh", null, Map.of("refreshToken", refresh));
        assertThat(first.status()).isEqualTo(200);
        Res second = post_("/auth/refresh", null, Map.of("refreshToken", refresh));
        assertThat(second.status()).isEqualTo(401);
        assertThat(second.errorCode()).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void protectedAndAdminRoutesNeedTheRightAccess() throws Exception {
        assertThat(get_("/users/me", null).status()).isEqualTo(401);
        assertThat(get_("/users/me", "not-a-token").status()).isEqualTo(401);
        String token = loginWithPhone(PHONE);
        assertThat(get_("/admin/overview", token).status()).isEqualTo(403);
    }

    @Test
    void blockedUsersAreStopped() throws Exception {
        String token = loginWithPhone(PHONE);
        jdbc.update("UPDATE users SET status = 'BLOCKED', blocked_reason = 'test', blocked_at = now()");
        Res res = get_("/users/me", token);
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.errorCode()).isEqualTo("ACCOUNT_BLOCKED");
    }
}
