package com.parknest.api.users;

import java.time.OffsetDateTime;
import java.util.List;

/** A row of the users table. */
public record User(
        String id,
        String name,
        String email,
        OffsetDateTime emailVerifiedAt,
        String phone,
        OffsetDateTime phoneVerifiedAt,
        String avatarUrl,
        List<String> roles,
        String status,
        String hostStatus) {

    public boolean phoneVerified() {
        return phoneVerifiedAt != null;
    }

    public boolean blocked() {
        return "BLOCKED".equals(status);
    }
}
