package com.parknest.api.payments;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutRepository extends JpaRepository<Payout, String> {

    List<Payout> findByHostIdOrderByCreatedAtDesc(String hostId);

    List<Payout> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(sum(p.amountPaise), 0) from Payout p where p.hostId = :hostId and p.status = 'PAID' and p.paidAt >= :from")
    long paidSince(@Param("hostId") String hostId, @Param("from") OffsetDateTime from);

    long countByStatus(String status);

    @Query("select coalesce(sum(p.amountPaise), 0) from Payout p where p.status = :status")
    long sumByStatus(@Param("status") String status);
}
