package com.parknest.api.users;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleRepository {

    private final JdbcClient db;

    public VehicleRepository(JdbcClient db) {
        this.db = db;
    }

    public record Vehicle(String id, String registrationNumber, String type, String nickname, boolean isDefault) {}

    /** Saved vehicles, default first, then oldest first. */
    public List<Vehicle> listForUser(String userId) {
        return db.sql("""
                SELECT id, registration_number, type, nickname, is_default FROM vehicles
                WHERE user_id = :user AND deleted_at IS NULL
                ORDER BY is_default DESC, created_at
                """)
                .param("user", userId)
                .query((rs, n) -> new Vehicle(rs.getString("id"), rs.getString("registration_number"), rs.getString("type"),
                        rs.getString("nickname"), rs.getBoolean("is_default")))
                .list();
    }

    public Optional<Vehicle> find(String userId, String id) {
        return listForUser(userId).stream().filter(v -> v.id().equals(id)).findFirst();
    }

    public void insert(String id, String userId, String registrationNumber, String type, String nickname, boolean isDefault) {
        db.sql("""
                INSERT INTO vehicles (id, user_id, registration_number, type, nickname, is_default)
                VALUES (:id, :user, :reg, :type, :nickname, :isDefault)
                """)
                .param("id", id).param("user", userId).param("reg", registrationNumber).param("type", type)
                .param("nickname", nickname).param("isDefault", isDefault)
                .update();
    }

    public void clearDefault(String userId) {
        db.sql("UPDATE vehicles SET is_default = false WHERE user_id = :user AND is_default").param("user", userId).update();
    }

    public void setDefault(String userId, String id) {
        db.sql("UPDATE vehicles SET is_default = true WHERE user_id = :user AND id = :id").param("user", userId).param("id", id).update();
    }

    public void setNickname(String userId, String id, String nickname) {
        db.sql("UPDATE vehicles SET nickname = :nickname WHERE user_id = :user AND id = :id")
                .param("user", userId).param("id", id).param("nickname", nickname).update();
    }

    public void softDelete(String userId, String id) {
        db.sql("UPDATE vehicles SET deleted_at = now(), is_default = false WHERE user_id = :user AND id = :id")
                .param("user", userId).param("id", id).update();
    }
}
