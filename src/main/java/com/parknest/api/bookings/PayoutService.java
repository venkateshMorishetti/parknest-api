package com.parknest.api.bookings;

import com.parknest.api.common.Ids;
import com.parknest.api.common.Responses;
import com.parknest.api.hosting.PayoutAccount;
import com.parknest.api.hosting.PayoutAccountRepository;
import com.parknest.api.payments.Payout;
import com.parknest.api.payments.PayoutItem;
import com.parknest.api.payments.PayoutItemRepository;
import com.parknest.api.payments.PayoutRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Groups each host's finished bookings into a payout once a day. Sending the money needs a payouts provider
 * (RazorpayX, Cashfree Payouts...), which is not chosen yet, so payouts stay SCHEDULED until one is added.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    /** When the daily job runs (IST). Keep in step with BookingJobs. */
    static final LocalTime RUN_AT = LocalTime.of(10, 0);

    private final BookingRepository bookings;
    private final PayoutRepository payouts;
    private final PayoutItemRepository items;
    private final PayoutAccountRepository accounts;

    public PayoutService(BookingRepository bookings, PayoutRepository payouts, PayoutItemRepository items, PayoutAccountRepository accounts) {
        this.bookings = bookings;
        this.payouts = payouts;
        this.items = items;
        this.accounts = accounts;
    }

    /** Creates one SCHEDULED payout per host for bookings not yet paid out. Hosts without a payout account are skipped. */
    @Transactional
    public List<Payout> schedulePayouts() {
        Map<String, List<Booking>> byHost = new LinkedHashMap<>();
        for (Booking b : bookings.completedNotPaidOut()) byHost.computeIfAbsent(b.getHostId(), k -> new ArrayList<>()).add(b);
        List<Payout> created = new ArrayList<>();
        LocalDate today = LocalDate.now(Responses.IST);
        byHost.forEach((hostId, list) -> {
            Optional<PayoutAccount> account = accounts.findByUserIdAndReplacedAtIsNull(hostId);
            if (account.isEmpty()) {
                log.warn("Host {} has {} bookings to pay out but no payout account", hostId, list.size());
                return;
            }
            Payout p = new Payout();
            p.setId(Ids.newId("po"));
            p.setHostId(hostId);
            p.setPayoutAccountId(account.get().getId());
            p.setAmountPaise(list.stream().mapToLong(Booking::getHostEarningsPaise).sum());
            p.setScheduledFor(today);
            payouts.saveAndFlush(p);
            for (Booking b : list) items.save(new PayoutItem(p.getId(), b.getId(), b.getHostEarningsPaise()));
            created.add(p);
        });
        if (!created.isEmpty()) log.info("Scheduled {} payouts", created.size());
        return created;
    }

    /** The date the daily job next runs. */
    static LocalDate nextRunDate(ZonedDateTime now) {
        return now.toLocalTime().isBefore(RUN_AT) ? now.toLocalDate() : now.toLocalDate().plusDays(1);
    }
}
