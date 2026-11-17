package com.parknest.api.bookings;

import com.parknest.api.bookings.BookingDtos.BookingRequest;
import com.parknest.api.bookings.BookingDtos.Guest;
import com.parknest.api.bookings.BookingDtos.HostVehicle;
import com.parknest.api.common.ApiException;
import com.parknest.api.common.Money;
import com.parknest.api.common.Responses;
import com.parknest.api.common.Times;
import com.parknest.api.hosting.HostingService;
import com.parknest.api.hosting.PayoutAccount;
import com.parknest.api.hosting.PayoutAccountRepository;
import com.parknest.api.listings.Listing;
import com.parknest.api.listings.ListingRepository;
import com.parknest.api.payments.Payout;
import com.parknest.api.payments.PayoutItemRepository;
import com.parknest.api.payments.PayoutRepository;
import com.parknest.api.users.User;
import com.parknest.api.users.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The host's side of bookings: dashboard, approving or declining requests, the bookings list and earnings. */
@Service
public class HostBookingService {

    /** Bookings that earn the host money (paid, in use or finished). */
    static final Set<String> EARNING = Set.of("CONFIRMED", "CHECKED_IN", "COMPLETED");
    /** Bookings that hold a bay. */
    static final Set<String> HOLDING_BAY = Set.of("PENDING_PAYMENT", "CONFIRMED", "CHECKED_IN");
    static final String AUTO_DECLINE_REASON = "The spot was booked by another driver for these times.";

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final ListingRepository listings;
    private final UserRepository users;
    private final HostingService hosting;
    private final PayoutRepository payouts;
    private final PayoutItemRepository payoutItems;
    private final PayoutAccountRepository payoutAccounts;
    private final int approvalPayHours;

    public HostBookingService(BookingRepository bookings, BookingService bookingService, ListingRepository listings, UserRepository users,
                              HostingService hosting, PayoutRepository payouts, PayoutItemRepository payoutItems,
                              PayoutAccountRepository payoutAccounts, @Value("${parknest.bookings.approval-pay-hours:12}") int approvalPayHours) {
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.listings = listings;
        this.users = users;
        this.hosting = hosting;
        this.payouts = payouts;
        this.payoutItems = payoutItems;
        this.payoutAccounts = payoutAccounts;
        this.approvalPayHours = approvalPayHours;
    }

    // ---------- Dashboard ----------

    /** GET /host/dashboard */
    @Transactional
    public BookingDtos.Dashboard dashboard(String hostId) {
        hosting.requireHost(hostId);
        bookingService.expireHolds();
        OffsetDateTime now = OffsetDateTime.now();
        YearMonth month = YearMonth.now(Responses.IST);
        OffsetDateTime monthStart = startOf(month);
        OffsetDateTime monthEnd = startOf(month.plusMonths(1));

        List<Booking> thisMonth = earning(bookings.findByHostIdAndStartAtBetween(hostId, monthStart, monthEnd.minusNanos(1)));
        List<Booking> lastMonth = earning(bookings.findByHostIdAndStartAtBetween(hostId, startOf(month.minusMonths(1)), monthStart.minusNanos(1)));
        long earned = sum(thisMonth);
        long earnedBefore = sum(lastMonth);
        int change = earnedBefore == 0 ? 0 : (int) Math.round((earned - earnedBefore) * 100.0 / earnedBefore);

        List<Booking> upcoming = bookings.findByHostIdAndStatusInOrderByStartAtAsc(hostId, List.of("CONFIRMED", "PENDING_PAYMENT"))
                .stream().filter(b -> b.getStartAt().isAfter(now)).toList();

        List<Listing> mine = listings.findByHostIdAndDeletedAtIsNullOrderByCreatedAtDesc(hostId);
        int occupancy = occupancyPercent(mine, bookings.findByHostIdAndStatusInOrderByStartAtAsc(hostId, EARNING), monthStart, monthEnd);
        Map<String, Long> perListing = thisMonth.stream().collect(Collectors.groupingBy(Booking::getListingId, Collectors.counting()));

        Pending pending = pending(hostId);
        BookingDtos.DashboardSummary summary = new BookingDtos.DashboardSummary(
                new BookingDtos.EarningsThisMonth("INR", Money.ofPaise(earned).amount(), change), thisMonth.size(), upcoming.size(), occupancy,
                Money.ofPaise(pending.paise()), pending.nextDate());

        List<Booking> waiting = bookings.findByHostIdAndStatusOrderByCreatedAtAsc(hostId, "PENDING_HOST_APPROVAL").stream()
                .filter(b -> b.getEndAt().isAfter(now)).toList();
        Map<String, User> drivers = usersById(waiting.stream().map(Booking::getDriverId).toList());
        List<BookingRequest> requests = waiting.stream().map(b -> new BookingRequest(b.getId(), "PENDING", guest(drivers.get(b.getDriverId())),
                new HostVehicle(b.getVehicleType(), b.getVehicleRegistration()), b.getListingId(), Times.ist(b.getStartAt()), Times.ist(b.getEndAt()),
                b.chargedDays(), Money.ofPaise(b.getTotalPaise()),
                waiting.stream().filter(o -> o != b && o.getListingId().equals(b.getListingId())
                        && o.getStartAt().isBefore(b.getEndAt()) && o.getEndAt().isAfter(b.getStartAt())).map(Booking::getId).toList())).toList();

        List<BookingDtos.DashboardListing> cards = mine.stream().map(l -> new BookingDtos.DashboardListing(l.getId(), l.getTitle(), l.getBayLabel(),
                Money.ofPaise(l.getPricePerDayPaise()).amount(), l.getStatus(), perListing.getOrDefault(l.getId(), 0L).intValue())).toList();
        return new BookingDtos.Dashboard(summary, requests, cards);
    }

    // ---------- Requests ----------

    /** POST /host/booking-requests/{id}/decision. Approving asks the driver to pay; other requests that no longer fit are declined. */
    @Transactional
    public BookingDtos.DecisionResult decide(String hostId, String id, BookingDtos.DecisionRequest req) {
        hosting.requireHost(hostId);
        String decision = req == null ? null : req.decision();
        if (!"APPROVE".equals(decision) && !"DECLINE".equals(decision)) {
            throw ApiException.invalid("INVALID_DECISION", "decision", "Choose approve or decline");
        }
        Booking b = bookings.findForUpdate(id).filter(x -> x.getHostId().equals(hostId)).orElseThrow(() -> ApiException.notFound("Booking request"));
        if (!b.is("PENDING_HOST_APPROVAL")) {
            throw ApiException.conflict("ALREADY_DECIDED", null, b.is("DECLINED") ? "This request was already declined"
                    : b.is("CANCELLED") ? "The driver cancelled this request" : "This request was already approved");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if ("DECLINE".equals(decision)) {
            String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
            if (reason != null && reason.length() > 300) throw ApiException.invalid("reason", "Keep the reason under 300 characters");
            b.setStatus("DECLINED");
            b.setHostDecidedAt(now);
            b.setDeclineReason(reason);
            return new BookingDtos.DecisionResult(b.getId(), "DECLINED", null, List.of());
        }

        if (!b.getEndAt().isAfter(now)) throw ApiException.conflict("BOOKING_ENDED", null, "These times have already passed");
        Listing listing = listings.findForUpdate(b.getListingId()).orElseThrow(() -> ApiException.notFound("Listing"));
        if (!listing.isActive()) throw ApiException.conflict("LISTING_NOT_ACTIVE", null, "Turn this spot back on before approving requests");
        bookingService.requireFreeBay(listing, b.getStartAt(), b.getEndAt(), null);

        OffsetDateTime payBy = now.plusHours(approvalPayHours);
        OffsetDateTime limit = b.getStartAt().isAfter(now) ? b.getStartAt() : b.getEndAt();
        b.setStatus("PENDING_PAYMENT");
        b.setHostDecidedAt(now);
        b.setHoldExpiresAt(payBy.isBefore(limit) ? payBy : limit);
        bookings.saveAndFlush(b);

        List<String> declined = new ArrayList<>();
        for (Booking other : bookings.overlappingRequests(b.getListingId(), b.getId(), b.getStartAt(), b.getEndAt())) {
            if (bookings.peakOccupancy(listing.getId(), other.getStartAt(), other.getEndAt(), null) >= listing.getBays()) {
                other.setStatus("DECLINED");
                other.setHostDecidedAt(now);
                other.setDeclineReason(AUTO_DECLINE_REASON);
                other.setDeclinedByBookingId(b.getId());
                declined.add(other.getId());
            }
        }
        return new BookingDtos.DecisionResult(b.getId(), "APPROVED", b.getId(), declined);
    }

    // ---------- Bookings ----------

    public record Page(List<BookingDtos.HostBooking> items, Map<String, Integer> counts) {}

    /** GET /host/bookings?tab=upcoming|current|past. Only paid bookings; requests are on the dashboard. */
    @Transactional(readOnly = true)
    public Page list(String hostId, String tab) {
        hosting.requireHost(hostId);
        String t = tab == null ? "upcoming" : tab;
        if (!Set.of("upcoming", "current", "past").contains(t)) throw ApiException.invalid("tab", "tab must be upcoming, current or past");
        List<Booking> all = bookings.findByHostIdAndStatusInOrderByStartAtAsc(hostId, List.of("CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED"))
                .stream().filter(b -> !b.is("CANCELLED") || b.getConfirmedAt() != null).toList();
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("upcoming", 0, "current", 0, "past", 0));
        for (Booking b : all) counts.merge(BookingService.tabOf(b), 1, Integer::sum);
        List<Booking> chosen = new ArrayList<>(all.stream().filter(b -> BookingService.tabOf(b).equals(t)).toList());
        if (t.equals("past")) chosen.sort(Comparator.comparing(Booking::getStartAt).reversed());

        Map<String, User> drivers = usersById(chosen.stream().map(Booking::getDriverId).toList());
        Map<String, Listing> spots = listings.findAllById(chosen.stream().map(Booking::getListingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
        List<BookingDtos.HostBooking> items = chosen.stream().map(b -> {
            Listing l = spots.get(b.getListingId());
            return new BookingDtos.HostBooking(b.getId(), b.getBookingRef(), b.getStatus(), guest(drivers.get(b.getDriverId())),
                    new HostVehicle(b.getVehicleType(), b.getVehicleRegistration()),
                    new BookingDtos.HostListingRef(b.getListingId(), l == null ? "Parking spot" : l.getTitle()), Times.ist(b.getStartAt()),
                    Times.ist(b.getEndAt()), b.chargedDays(), Money.ofPaise(b.getHostEarningsPaise()), Times.ist(b.getCheckedInAt()),
                    Times.ist(b.getCheckedOutAt()));
        }).toList();
        return new Page(items, counts);
    }

    // ---------- Earnings ----------

    /** GET /host/earnings: this month, what is waiting to be paid, the last 6 months and the payouts. */
    @Transactional(readOnly = true)
    public BookingDtos.Earnings earnings(String hostId) {
        hosting.requireHost(hostId);
        YearMonth month = YearMonth.now(Responses.IST);
        OffsetDateTime from = startOf(month.minusMonths(5));
        List<Booking> recent = earning(bookings.findByHostIdAndStartAtBetween(hostId, from, startOf(month.plusMonths(1)).minusNanos(1)));
        Map<YearMonth, Long> byMonth = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) byMonth.put(month.minusMonths(i), 0L);
        for (Booking b : recent) byMonth.merge(YearMonth.from(b.getStartAt().atZoneSameInstant(Responses.IST)), b.getHostEarningsPaise(), Long::sum);
        List<BookingDtos.MonthAmount> monthly = byMonth.entrySet().stream()
                .map(e -> new BookingDtos.MonthAmount(e.getKey().toString(), Money.ofPaise(e.getValue()).amount())).toList();

        Pending pending = pending(hostId);
        OffsetDateTime yearStart = ZonedDateTime.of(month.getYear(), 1, 1, 0, 0, 0, 0, Responses.IST).toOffsetDateTime();
        BookingDtos.EarningsSummary summary = new BookingDtos.EarningsSummary(Money.ofPaise(byMonth.get(month)), Money.ofPaise(pending.paise()),
                Money.ofPaise(payouts.paidSince(hostId, yearStart)), pending.nextDate());

        List<Payout> list = payouts.findByHostIdOrderByCreatedAtDesc(hostId);
        Map<String, PayoutAccount> accounts = payoutAccounts.findAllById(list.stream().map(Payout::getPayoutAccountId).distinct().toList()).stream()
                .collect(Collectors.toMap(PayoutAccount::getId, Function.identity()));
        List<BookingDtos.PayoutView> views = list.stream().map(p -> payoutView(p, accounts.get(p.getPayoutAccountId()), payoutItems.countByPayoutId(p.getId())))
                .toList();
        return new BookingDtos.Earnings(summary, monthly, views);
    }

    public static BookingDtos.PayoutView payoutView(Payout p, PayoutAccount account, long bookingCount) {
        String destination = account == null ? null : ("UPI".equals(account.getMethod()) ? "UPI · " : "Bank · ") + account.getDisplay();
        return new BookingDtos.PayoutView(p.getId(), p.getStatus(), Money.ofPaise(p.getAmountPaise()), (int) bookingCount,
                Times.ist(p.getInitiatedAt()), Times.ist(p.getPaidAt()), destination, p.getFailureReason());
    }

    // ---------- Shared ----------

    private record Pending(long paise, String nextDate) {}

    /** Completed bookings not yet in a payout, plus payouts not yet paid. */
    private Pending pending(String hostId) {
        long unbatched = bookings.completedNotPaidOutForHost(hostId).stream().mapToLong(Booking::getHostEarningsPaise).sum();
        List<Payout> open = payouts.findByHostIdOrderByCreatedAtDesc(hostId).stream().filter(p -> !"PAID".equals(p.getStatus())).toList();
        long total = unbatched + open.stream().mapToLong(Payout::getAmountPaise).sum();
        if (total == 0) return new Pending(0, null);
        LocalDate next = open.stream().map(Payout::getScheduledFor).min(Comparator.naturalOrder())
                .orElseGet(() -> PayoutService.nextRunDate(ZonedDateTime.now(Responses.IST)));
        LocalDate today = LocalDate.now(Responses.IST);
        return new Pending(total, (next.isBefore(today) ? today : next).toString());
    }

    /** Booked bay-hours this month over the bay-hours of the host's live spots. */
    private static int occupancyPercent(List<Listing> mine, List<Booking> paid, OffsetDateTime from, OffsetDateTime to) {
        long bays = mine.stream().filter(l -> Set.of("ACTIVE", "PAUSED").contains(l.getStatus())).mapToLong(Listing::getBays).sum();
        if (bays == 0) return 0;
        long capacity = bays * Duration.between(from, to).toMinutes();
        long used = 0;
        for (Booking b : paid) {
            OffsetDateTime s = b.getStartAt().isAfter(from) ? b.getStartAt() : from;
            OffsetDateTime e = b.getEndAt().isBefore(to) ? b.getEndAt() : to;
            if (e.isAfter(s)) used += Duration.between(s, e).toMinutes();
        }
        return (int) Math.min(100, Math.round(used * 100.0 / capacity));
    }

    private static List<Booking> earning(List<Booking> list) {
        return list.stream().filter(b -> EARNING.contains(b.getStatus()) || (b.is("CANCELLED") && b.getHostEarningsPaise() > 0)).toList();
    }

    private static long sum(List<Booking> list) {
        return list.stream().mapToLong(Booking::getHostEarningsPaise).sum();
    }

    private static OffsetDateTime startOf(YearMonth m) {
        return m.atDay(1).atStartOfDay(Responses.IST).toOffsetDateTime();
    }

    private Map<String, User> usersById(Collection<String> ids) {
        return users.findAllById(ids.stream().distinct().toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** "Arun Kumar" -> "Arun K": hosts see a first name and an initial. */
    static Guest guest(User u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return new Guest("ParkNest driver");
        String[] parts = u.getName().trim().split("\\s+");
        return new Guest(parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0));
    }
}
