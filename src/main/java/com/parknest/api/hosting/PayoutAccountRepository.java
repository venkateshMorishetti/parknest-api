package com.parknest.api.hosting;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, String> {

    /** The account in use. */
    Optional<PayoutAccount> findByUserIdAndReplacedAtIsNull(String userId);

    @Modifying(flushAutomatically = true)
    @Query("update PayoutAccount p set p.replacedAt = :now where p.userId = :userId and p.replacedAt is null")
    int retireCurrent(@Param("userId") String userId, @Param("now") OffsetDateTime now);
}
