package com.parknest.api.auth;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, String> {

    /** A live session for this refresh token, locked so it can be used only once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.refreshTokenHash = :hash and s.revokedAt is null and s.expiresAt > :now")
    Optional<Session> findLiveForUpdate(@Param("hash") String hash, @Param("now") OffsetDateTime now);

    Optional<Session> findByRefreshTokenHashAndRevokedAtIsNull(String hash);

    @Modifying
    @Query("update Session s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") String userId, @Param("now") OffsetDateTime now);
}
