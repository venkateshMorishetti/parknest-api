package com.parknest.api.payments;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, String> {

    Optional<Refund> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<Refund> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
