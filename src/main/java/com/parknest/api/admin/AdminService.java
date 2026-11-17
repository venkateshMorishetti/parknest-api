package com.parknest.api.admin;

import com.parknest.api.auth.SessionRepository;
import com.parknest.api.bookings.Booking;
import com.parknest.api.bookings.BookingRepository;
import com.parknest.api.bookings.BookingService;
import com.parknest.api.bookings.HostBookingService;
import com.parknest.api.common.ApiException;
import com.parknest.api.common.Geo;
import com.parknest.api.common.Money;
import com.parknest.api.common.Responses;
import com.parknest.api.common.Times;
import com.parknest.api.files.FileUrls;
import com.parknest.api.hosting.HostProfile;
import com.parknest.api.hosting.HostProfileRepository;
import com.parknest.api.hosting.PayoutAccount;
import com.parknest.api.hosting.PayoutAccountRepository;
import com.parknest.api.listings.Listing;
import com.parknest.api.listings.ListingRepository;
import com.parknest.api.payments.Payment;
import com.parknest.api.payments.PaymentGateway;
import com.parknest.api.payments.PaymentRepository;
import com.parknest.api.payments.Payout;
import com.parknest.api.payments.PayoutItemRepository;
import com.parknest.api.payments.PayoutRepository;
import com.parknest.api.payments.Refund;
import com.parknest.api.payments.RefundRepository;
import com.parknest.api.users.User;
import com.parknest.api.users.UserRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin panel: spot review, users, bookings, refunds, payouts and the audit log. Every change writes an admin_actions row. */
@Service
public class AdminService {

    static final int MAX_ROWS = 200;
    static final Set<String> ADMIN_CANCELLABLE = Set.of("PENDING_PAYMENT", "PENDING_HOST_APPROVAL", "CONFIRMED", "CHECKED_IN");
    static final Set<String> BOOKING_STATUSES = Set.of("PENDING_HOST_APPROVAL", "PENDING_PAYMENT", "CONFIRMED", "CHECKED_IN", "COMPLETED",
            "CANCELLED", "DECLINED", "EXPIRED");

    private final ListingRepository listings;
    private final UserRepository users;
    private final HostProfileRepository hostProfiles;
    private final SessionRepository sessions;
    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PayoutRepository payouts;
    private final PayoutItemRepository payoutItems;
    private final PayoutAccountRepository payoutAccounts;
    private final AdminActionRepository actions;
    private final PaymentGateway gateway;

    public AdminService(ListingRepository listings, UserRepository users, HostProfileRepository hostProfiles, SessionRepository sessions,
                        BookingRepository bookings, BookingService bookingService, PaymentRepository payments, RefundRepository refunds,
                        PayoutRepository payouts, PayoutItemRepository payoutItems, PayoutAccountRepository payoutAccounts,
                        AdminActionRepository actions, PaymentGateway gateway) {
        this.listings = listings;
        this.users = users;
        this.hostProfiles = hostProfiles;
        this.sessions = sessions;
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.payments = payments;
        this.refunds = refunds;
        this.payouts = payouts;
        this.payoutItems = payoutItems;
        this.payoutAccounts = payoutAccounts;
        this.actions = actions;
        this.gateway = gateway;
    }

    // ---------- Overview ----------

    @Transactional(readOnly = true)
    public AdminDtos.Overview overview() {
        OffsetDateTime today = LocalDate.now(Responses.IST).atStartOfDay(Responses.IST).toOffsetDateTime();
        return new AdminDtos.Overview(listings.countByStatusAndDeletedAtIsNull("NEEDS_APPROVAL"), bookings.countByConfirmedAtGreaterThanEqual(today),
                bookings.countByStatus("CHECKED_IN"), refunds.countByStatus("PROCESSING"), refunds.countByStatus("FAILED"),
                payouts.countByStatus("FAILED"),
                new AdminDtos.ScheduledPayouts(payouts.countByStatus("SCHEDULED"), Money.ofPaise(payouts.sumByStatus("SCHEDULED"))),
                activity(5));
    }

    // ---------- Spots ----------

    public record Page<T>(List<T> items, Map<String, Object> meta) {}

    /** GET /admin/listings?status= (default NEEDS_APPROVAL, oldest first), with counts per status. */
    @Transactional(readOnly = true)
    public Page<AdminDtos.Listing> listings(String status) {
        String s = status == null || status.isBlank() ? "NEEDS_APPROVAL" : status;
        if (!Set.of("NEEDS_APPROVAL", "ACTIVE", "REJECTED", "PAUSED").contains(s)) {
            throw ApiException.invalid("status", "status must be NEEDS_APPROVAL, ACTIVE, PAUSED or REJECTED");
        }
        List<Listing> list = listings.findByStatusAndDeletedAtIsNullOrderBySubmittedAtAsc(s);
        Map<String, User> people = usersById(list.stream().map(Listing::getHostId).toList());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String st : List.of("NEEDS_APPROVAL", "ACTIVE", "REJECTED")) counts.put(st, listings.countByStatusAndDeletedAtIsNull(st));
        return new Page<>(list.stream().map(l -> listing(l, people)).toList(), Map.of("counts", counts));
    }

    @Transactional(readOnly = true)
    public AdminDtos.Listing listing(String id) {
        Listing l = listings.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("Listing"));
        return listing(l, usersById(List.of(l.getHostId())));
    }

    /** POST /admin/listings/{id}/decision: APPROVE makes it bookable; REJECT needs a reason the host will see. */
    @Transactional
    public AdminDtos.DecisionResult decideListing(String adminId, String id, AdminDtos.DecisionRequest req) {
        String decision = req == null ? null : req.decision();
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) throw ApiException.invalid("INVALID_DECISION", "decision", "Choose approve or reject");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if ("REJECT".equals(decision) && reason.length() < 10) {
            throw ApiException.invalid("reason", "Tell the host what to fix (at least 10 characters)");
        }
        Listing l = listings.findForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("Listing"));
        if (!"NEEDS_APPROVAL".equals(l.getStatus())) throw ApiException.conflict("ALREADY_DECIDED", null, "This spot is not waiting for review");
        OffsetDateTime now = OffsetDateTime.now();
        boolean approve = "APPROVE".equals(decision);
        l.setStatus(approve ? "ACTIVE" : "REJECTED");
        l.setReviewReason(null);
        l.setReviewNote(approve ? null : reason);
        l.setReviewedAt(now);
        l.setReviewedBy(adminId);
        actions.save(new AdminAction(adminId, approve ? "LISTING_APPROVED" : "LISTING_REJECTED", "LISTING", l.getId(), l.getTitle(),
                reason.isEmpty() ? null : reason));
        return new AdminDtos.DecisionResult(l.getId(), l.getStatus(), Times.ist(now));
    }

    // ---------- Users ----------

    @Transactional(readOnly = true)
    public Page<AdminDtos.User> users(String q) {
        List<User> list = users.search(norm(q), PageRequest.of(0, MAX_ROWS));
        Map<String, HostProfile> hosts = hostProfiles.findAllById(list.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(HostProfile::getUserId, Function.identity()));
        List<AdminDtos.User> items = list.stream().map(u -> new AdminDtos.User(u.getId(), u.getName(), u.getPhone(), u.getEmail(),
                Times.ist(u.getCreatedAt()), hosts.containsKey(u.getId()) ? hosts.get(u.getId()).getStatus() : "NOT_STARTED",
                bookings.countByDriverId(u.getId()), listings.countByHostIdAndDeletedAtIsNull(u.getId()), u.getStatus(),
                Times.ist(u.getLastActiveAt() != null ? u.getLastActiveAt() : u.getCreatedAt()))).toList();
        return new Page<>(items, Map.of("total", items.size()));
    }

    /** PATCH /admin/users/{id}: block (ends their sessions) or unblock. */
    @Transactional
    public AdminDtos.UserUpdateResult updateUser(String adminId, String id, AdminDtos.UserUpdate req) {
        String status = req == null ? null : req.status();
        if (!"ACTIVE".equals(status) && !"BLOCKED".equals(status)) throw ApiException.invalid("status", "status must be ACTIVE or BLOCKED");
        String reason = req.reason() == null ? "" : req.reason().trim();
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        OffsetDateTime now = OffsetDateTime.now();
        if ("BLOCKED".equals(status)) {
            if (u.getId().equals(adminId)) throw ApiException.conflict("CANNOT_BLOCK_SELF", null, "You cannot block your own account");
            if (reason.length() < 5) throw ApiException.invalid("reason", "Add a reason for blocking");
            if (!u.isBlocked()) {
                u.setStatus("BLOCKED");
                u.setBlockedAt(now);
                u.setBlockedReason(reason);
                sessions.revokeAll(u.getId(), now);
                actions.save(new AdminAction(adminId, "USER_BLOCKED", "USER", u.getId(), u.getName(), reason));
            }
        } else if (u.isBlocked()) {
            u.setStatus("ACTIVE");
            u.setBlockedAt(null);
            u.setBlockedReason(null);
            actions.save(new AdminAction(adminId, "USER_UNBLOCKED", "USER", u.getId(), u.getName(), reason.isEmpty() ? null : reason));
        }
        users.saveAndFlush(u);
        return new AdminDtos.UserUpdateResult(u.getId(), u.getStatus(), Times.ist(u.getUpdatedAt()));
    }

    // ---------- Bookings ----------

    @Transactional
    public Page<AdminDtos.Booking> bookings(String q, String status) {
        bookingService.expireHolds();
        String s = status == null ? "" : status.trim();
        if (!s.isEmpty() && !BOOKING_STATUSES.contains(s)) throw ApiException.invalid("status", "Unknown booking status");
        List<Booking> list = bookings.adminSearch(norm(q), s, PageRequest.of(0, MAX_ROWS));
        Map<String, User> people = usersById(list.stream().flatMap(b -> java.util.stream.Stream.of(b.getDriverId(), b.getHostId())).toList());
        Map<String, Listing> spots = listings.findAllById(list.stream().map(Booking::getListingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
        List<AdminDtos.Booking> items = list.stream().map(b -> {
            User d = people.get(b.getDriverId());
            User h = people.get(b.getHostId());
            Listing l = spots.get(b.getListingId());
            return new AdminDtos.Booking(b.getId(), b.getBookingRef(), b.getStatus(), b.getBookingMode(),
                    new AdminDtos.Person(d.getId(), d.getName(), d.getPhone(), null), new AdminDtos.Person(h.getId(), h.getName(), null, null),
                    new AdminDtos.ListingRef(b.getListingId(), l == null ? "" : l.getTitle()), Times.ist(b.getStartAt()), Times.ist(b.getEndAt()),
                    Money.ofPaise(b.getTotalPaise()), Money.ofPaise(b.getHostEarningsPaise()), Times.ist(b.getCreatedAt()));
        }).toList();
        return new Page<>(items, Map.of("total", items.size()));
    }

    /** POST /admin/bookings/{id}/cancel: ParkNest cancels and refunds the driver in full; the host earns nothing. */
    @Transactional
    public AdminDtos.CancelResult cancelBooking(String adminId, String id, AdminDtos.CancelRequest req) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < 5) throw ApiException.invalid("reason", "Add a reason; the driver and host will see it");
        if (req.refund() != null && !"FULL".equals(req.refund())) throw ApiException.invalid("refund", "Only full refunds are supported");
        bookingService.expireHolds();
        Booking b = bookings.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!ADMIN_CANCELLABLE.contains(b.getStatus())) {
            throw ApiException.conflict("CANCEL_NOT_ALLOWED", null, "Only bookings that are pending, confirmed or in progress can be cancelled");
        }
        long refundPaise = b.getConfirmedAt() != null ? b.getTotalPaise() : 0;
        Refund refund = bookingService.cancelAndRefund(b, "ADMIN", reason, refundPaise);
        b.setHostEarningsPaise(0);
        actions.save(new AdminAction(adminId, "BOOKING_CANCELLED", "BOOKING", b.getId(), b.getBookingRef(), reason));
        return new AdminDtos.CancelResult(b.getId(), "CANCELLED", Money.ofPaise(refundPaise),
                refund == null ? "NOT_APPLICABLE" : refund.getStatus());
    }

    // ---------- Refunds ----------

    @Transactional(readOnly = true)
    public Page<AdminDtos.Refund> refunds() {
        List<Refund> list = refunds.findAllByOrderByCreatedAtDesc();
        Map<String, Booking> byBooking = bookings.findAllById(list.stream().map(Refund::getBookingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        Map<String, User> drivers = usersById(byBooking.values().stream().map(Booking::getDriverId).toList());
        Map<String, Payment> paid = payments.findAllById(list.stream().map(Refund::getPaymentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        List<AdminDtos.Refund> items = list.stream().map(r -> {
            Booking b = byBooking.get(r.getBookingId());
            User d = drivers.get(b.getDriverId());
            Payment p = paid.get(r.getPaymentId());
            return new AdminDtos.Refund(r.getId(), new AdminDtos.BookingRef(b.getId(), b.getBookingRef()),
                    new AdminDtos.Person(d.getId(), d.getName(), d.getPhone(), null), Money.ofPaise(r.getAmountPaise()), r.getReason(), r.getStatus(),
                    p == null || p.getMethod() == null ? "UPI" : p.getMethod(), Times.ist(r.getCreatedAt()), Times.ist(r.getCompletedAt()),
                    r.getFailureReason());
        }).toList();
        return new Page<>(items, Map.of("counts", counts(list.stream().map(Refund::getStatus).toList(), List.of("PROCESSING", "FAILED", "REFUNDED"))));
    }

    /** POST /admin/refunds/{id}/retry: only FAILED refunds; sends it to the payment provider again. */
    @Transactional
    public AdminDtos.Retried retryRefund(String adminId, String id) {
        Refund r = refunds.findById(id).orElseThrow(() -> ApiException.notFound("Refund"));
        if (!"FAILED".equals(r.getStatus())) throw ApiException.conflict("NOT_FAILED", null, "Only failed refunds can be sent again");
        Payment p = payments.findById(r.getPaymentId()).orElseThrow();
        r.setAttempts((short) (r.getAttempts() + 1));
        try {
            r.setProviderRefundId(gateway.refund(p.getProviderPaymentId(), r.getAmountPaise()));
            r.setStatus("PROCESSING");
            r.setFailureReason(null);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            r.setFailureReason("The payment provider did not accept the refund: " + e.getMessage());
        }
        Booking b = bookings.findById(r.getBookingId()).orElseThrow();
        actions.save(new AdminAction(adminId, "REFUND_RETRIED", "REFUND", r.getId(), "Refund for " + b.getBookingRef(), null));
        return new AdminDtos.Retried(r.getId(), r.getStatus());
    }

    // ---------- Payouts ----------

    @Transactional(readOnly = true)
    public Page<AdminDtos.Payout> payouts() {
        List<Payout> list = payouts.findAllByOrderByCreatedAtDesc();
        Map<String, User> hosts = usersById(list.stream().map(Payout::getHostId).toList());
        Map<String, PayoutAccount> accounts = payoutAccounts.findAllById(list.stream().map(Payout::getPayoutAccountId).distinct().toList()).stream()
                .collect(Collectors.toMap(PayoutAccount::getId, Function.identity()));
        List<AdminDtos.Payout> items = list.stream().map(p -> {
            var view = HostBookingService.payoutView(p, accounts.get(p.getPayoutAccountId()), payoutItems.countByPayoutId(p.getId()));
            User h = hosts.get(p.getHostId());
            return new AdminDtos.Payout(p.getId(), new AdminDtos.Person(h.getId(), h.getName(), null, null), view.amount(), view.bookingCount(),
                    view.destination(), p.getStatus(), Times.ist(p.getInitiatedAt() != null ? p.getInitiatedAt() : p.getCreatedAt()),
                    view.paidAt(), p.getFailureReason());
        }).toList();
        return new Page<>(items, Map.of("counts", counts(list.stream().map(Payout::getStatus).toList(), List.of("SCHEDULED", "PROCESSING", "FAILED", "PAID"))));
    }

    /** POST /admin/payouts/{id}/retry: only FAILED payouts. They go back in the queue for the next payout run. */
    @Transactional
    public AdminDtos.Retried retryPayout(String adminId, String id) {
        Payout p = payouts.findById(id).orElseThrow(() -> ApiException.notFound("Payout"));
        if (!"FAILED".equals(p.getStatus())) throw ApiException.conflict("NOT_FAILED", null, "Only failed payouts can be sent again");
        PayoutAccount current = payoutAccounts.findByUserIdAndReplacedAtIsNull(p.getHostId())
                .orElseThrow(() -> ApiException.conflict("NO_PAYOUT_ACCOUNT", null, "The host has no payout account. Ask them to add one."));
        p.setPayoutAccountId(current.getId());
        p.setStatus("SCHEDULED");
        p.setFailureReason(null);
        p.setScheduledFor(LocalDate.now(Responses.IST));
        User h = users.findById(p.getHostId()).orElseThrow();
        actions.save(new AdminAction(adminId, "PAYOUT_RETRIED", "PAYOUT", p.getId(), "Payout to " + h.getName(), null));
        return new AdminDtos.Retried(p.getId(), p.getStatus());
    }

    // ---------- Activity ----------

    @Transactional(readOnly = true)
    public List<AdminDtos.Activity> activity(int limit) {
        List<AdminAction> list = actions.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, limit, Sort.unsorted()));
        Map<String, User> actors = usersById(list.stream().map(AdminAction::getActorId).toList());
        return list.stream().map(a -> new AdminDtos.Activity("act_" + a.getId(), Times.ist(a.getCreatedAt()),
                actors.containsKey(a.getActorId()) ? actors.get(a.getActorId()).getName() : "ParkNest", a.getAction(),
                new AdminDtos.Target(a.getTargetType(), a.getTargetId(), String.valueOf(a.getMetadata().getOrDefault("label", a.getTargetId()))),
                a.getReason())).toList();
    }

    // ---------- Shared ----------

    private AdminDtos.Listing listing(Listing l, Map<String, User> people) {
        User h = people.get(l.getHostId());
        AdminDtos.DecisionInfo decision = null;
        if (l.getReviewedAt() != null && Set.of("ACTIVE", "PAUSED", "REJECTED").contains(l.getStatus())) {
            User by = l.getReviewedBy() == null ? null : users.findById(l.getReviewedBy()).orElse(null);
            decision = new AdminDtos.DecisionInfo("REJECTED".equals(l.getStatus()) ? "REJECT" : "APPROVE", l.getReviewNote(), Times.ist(l.getReviewedAt()),
                    by == null ? "ParkNest" : by.getName());
        }
        return new AdminDtos.Listing(l.getId(), l.getTitle(), new AdminDtos.ListingHost(h.getId(), h.getName(), h.getPhone(), h.isPhoneVerified()),
                l.getStatus(), Times.ist(l.getSubmittedAt() != null ? l.getSubmittedAt() : l.getCreatedAt()), l.getReviewReason(),
                new AdminDtos.Address(l.getLine1(), l.getArea(), l.getCity(), l.getState(), l.getPincode(),
                        new Geo.Point(l.getLat().doubleValue(), l.getLng().doubleValue())),
                l.getBayLabel(), l.getEntryInstructions(), l.getSpotType(), l.getBays(), l.getVehicleTypes(), l.getSecurity(),
                new AdminDtos.Pricing("INR", Money.ofPaise(l.getPricePerDayPaise()).amount(), Money.ofPaise(l.getPricePerHourPaise()).amount()),
                new AdminDtos.AccessHours(l.is24x7(), l.getOpensAt() == null ? null : l.getOpensAt().toString(),
                        l.getClosesAt() == null ? null : l.getClosesAt().toString()),
                l.getBookingMode(), l.getPhotos().stream().map(p -> new AdminDtos.Photo(p.getFileId(), FileUrls.of(p.getFileId()), p.getCaption())).toList(),
                decision);
    }

    private Map<String, User> usersById(Collection<String> ids) {
        return users.findAllById(ids.stream().distinct().toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static Map<String, Long> counts(List<String> statuses, List<String> keys) {
        Map<String, Long> counts = new LinkedHashMap<>();
        keys.forEach(k -> counts.put(k, 0L));
        statuses.forEach(s -> counts.merge(s, 1L, Long::sum));
        return counts;
    }

    private static String norm(String q) {
        return q == null ? "" : q.trim().toLowerCase();
    }
}
