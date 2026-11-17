package com.parknest.api.users;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String SELECT = """
            SELECT u.id, u.name, u.email, u.email_verified_at, u.phone, u.phone_verified_at, u.avatar_url, u.roles, u.status,
                   COALESCE(h.status, 'NOT_STARTED') AS host_status
            FROM users u LEFT JOIN host_profiles h ON h.user_id = u.id
            """;

    private final JdbcClient db;

    public UserRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<User> findById(String id) {
        return db.sql(SELECT + " WHERE u.id = :id").param("id", id).query(UserRepository::map).optional();
    }

    public Optional<User> findByPhone(String phone) {
        return db.sql(SELECT + " WHERE u.phone = :phone").param("phone", phone).query(UserRepository::map).optional();
    }

    public Optional<User> findByEmail(String email) {
        return db.sql(SELECT + " WHERE lower(u.email) = lower(:email)").param("email", email).query(UserRepository::map).optional();
    }

    /** Lightweight check run on every authenticated request. */
    public Optional<AccountState> accountState(String id) {
        return db.sql("SELECT status, phone_verified_at IS NOT NULL AS phone_verified FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new AccountState(rs.getString("status"), rs.getBoolean("phone_verified")))
                .optional();
    }

    public void insert(String id, String name, String email, boolean emailVerified, String phone, String avatarUrl) {
        db.sql("""
                INSERT INTO users (id, name, email, email_verified_at, phone, phone_verified_at, avatar_url)
                VALUES (:id, :name, :email, CASE WHEN :emailVerified THEN now() END, :phone, CASE WHEN :hasPhone THEN now() END, :avatar)
                """)
                .param("id", id).param("name", name).param("email", email).param("emailVerified", emailVerified)
                .param("phone", phone).param("hasPhone", phone != null).param("avatar", avatarUrl)
                .update();
    }

    public void updateProfile(String id, String name, String email, boolean keepEmailVerified) {
        db.sql("""
                UPDATE users SET name = :name, email = :email,
                       email_verified_at = CASE WHEN :keep THEN email_verified_at END
                WHERE id = :id
                """)
                .param("id", id).param("name", name).param("email", email).param("keep", keepEmailVerified)
                .update();
    }

    public void setVerifiedPhone(String id, String phone) {
        db.sql("UPDATE users SET phone = :phone, phone_verified_at = now() WHERE id = :id")
                .param("id", id).param("phone", phone).update();
    }

    public void touch(String id) {
        db.sql("UPDATE users SET last_active_at = now() WHERE id = :id").param("id", id).update();
    }

    public record AccountState(String status, boolean phoneVerified) {}

    static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getObject("email_verified_at", OffsetDateTime.class),
                rs.getString("phone"),
                rs.getObject("phone_verified_at", OffsetDateTime.class),
                rs.getString("avatar_url"),
                strings(rs.getArray("roles")),
                rs.getString("status"),
                rs.getString("host_status"));
    }

    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
