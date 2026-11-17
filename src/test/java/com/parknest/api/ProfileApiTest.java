package com.parknest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileApiTest extends IntegrationTest {

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        token = loginWithPhone("+919876543210");
    }

    @Test
    void updatesNameAndEmail() throws Exception {
        Res res = call(put("/api/v1/users/me"), token, Map.of("name", "  Venkatesh Kumar ", "email", "venkatesh@gmail.com"));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.data().get("name").asText()).isEqualTo("Venkatesh Kumar");
        assertThat(res.data().get("isEmailVerified").asBoolean()).isFalse();
    }

    @Test
    void reportsEveryInvalidField() throws Exception {
        Res res = call(put("/api/v1/users/me"), token, Map.of("name", "V", "email", "not-an-email"));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.body().get("errors")).hasSize(2);
    }

    @Test
    void savesVehiclesWithOneDefault() throws Exception {
        Res first = post_("/users/me/vehicles", token, Map.of("registrationNumber", "ka 01 ab 1234", "type", "SEDAN"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.data().get("registrationNumber").asText()).isEqualTo("KA01AB1234");
        assertThat(first.data().get("isDefault").asBoolean()).as("first vehicle is the default").isTrue();
        assertThat(first.data().get("label").asText()).isEqualTo("Car · Sedan");

        Res second = post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA03XY9988", "type", "SUV", "isDefault", true));
        JsonNode vehicles = get_("/users/me", token).data().get("vehicles");
        assertThat(vehicles).hasSize(2);
        assertThat(vehicles.get(0).get("id").asText()).isEqualTo(second.data().get("id").asText());
        assertThat(vehicles.get(1).get("isDefault").asBoolean()).isFalse();

        // Removing the default makes the other one the default.
        call(delete("/api/v1/users/me/vehicles/" + second.data().get("id").asText()), token, null);
        vehicles = get_("/users/me", token).data().get("vehicles");
        assertThat(vehicles).hasSize(1);
        assertThat(vehicles.get(0).get("isDefault").asBoolean()).isTrue();
    }

    @Test
    void rejectsDuplicatesBadNumbersAndASixthVehicle() throws Exception {
        post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1234", "type", "SEDAN"));
        assertThat(post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA 01 AB 1234", "type", "SEDAN")).errorCode()).isEqualTo("DUPLICATE_VEHICLE");
        assertThat(post_("/users/me/vehicles", token, Map.of("registrationNumber", "HELLO", "type", "SEDAN")).errorCode()).isEqualTo("INVALID_REGISTRATION");
        assertThat(post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1235", "type", "TRUCK")).status()).isEqualTo(422);
        for (int i = 2; i <= 5; i++) post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA05MN100" + i, "type", "BIKE"));
        assertThat(post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1299", "type", "BIKE")).errorCode()).isEqualTo("VEHICLE_LIMIT");
    }

    @Test
    void renamesAndChangesTheDefault() throws Exception {
        post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA01AB1234", "type", "SEDAN"));
        String id = post_("/users/me/vehicles", token, Map.of("registrationNumber", "KA03XY9988", "type", "SUV")).data().get("id").asText();
        Map<String, Object> change = new HashMap<>();
        change.put("nickname", "Family car");
        change.put("isDefault", true);
        Res res = call(patch("/api/v1/users/me/vehicles/" + id), token, change);
        assertThat(res.data().get("nickname").asText()).isEqualTo("Family car");
        assertThat(res.data().get("isDefault").asBoolean()).isTrue();
        assertThat(call(patch("/api/v1/users/me/vehicles/veh_missing"), token, change).status()).isEqualTo(404);
    }
}
