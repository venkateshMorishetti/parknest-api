package com.parknest.api.auth;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpRequestRepository extends JpaRepository<OtpRequest, String> {

    long countByPhoneAndCreatedAtAfter(String phone, OffsetDateTime since);

    Optional<OtpRequest> findFirstByPhoneOrderByCreatedAtDesc(String phone);

    /** Locks the row while a code is checked, so two tries at once cannot both pass. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OtpRequest o where o.id = :id")
    Optional<OtpRequest> findForUpdate(@Param("id") String id);
}
