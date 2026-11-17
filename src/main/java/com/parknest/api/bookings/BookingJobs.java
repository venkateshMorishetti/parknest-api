package com.parknest.api.bookings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background jobs: unpaid holds end every minute, and payouts are grouped every morning at 10:00 IST.
 * Turned off with parknest.jobs.enabled=false (the tests call the services directly).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "parknest.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BookingJobs {

    private final BookingService bookings;
    private final PayoutService payouts;

    public BookingJobs(BookingService bookings, PayoutService payouts) {
        this.bookings = bookings;
        this.payouts = payouts;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void expireHolds() {
        bookings.expireHolds();
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    public void schedulePayouts() {
        payouts.schedulePayouts();
    }
}
