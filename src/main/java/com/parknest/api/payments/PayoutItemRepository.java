package com.parknest.api.payments;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutItemRepository extends JpaRepository<PayoutItem, PayoutItem.Key> {

    long countByPayoutId(String payoutId);

    List<PayoutItem> findByBookingIdIn(java.util.Collection<String> bookingIds);
}
