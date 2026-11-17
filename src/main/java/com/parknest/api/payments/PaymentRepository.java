package com.parknest.api.payments;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    Optional<Payment> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(String bookingId, String status);
}
