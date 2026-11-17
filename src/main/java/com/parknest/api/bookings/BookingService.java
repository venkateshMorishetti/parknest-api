package com.parknest.api.bookings;

import com.parknest.api.bookings.BookingDtos.Cancellation;
import com.parknest.api.bookings.BookingDtos.Hold;
import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.common.Money;
import com.parknest.api.common.Times;
import com.parknest.api.files.FileUrls;
import com.parknest.api.listings.Listing;
import com.parknest.api.listings.ListingDtos;
import com.parknest.api.listings.ListingRepository;
import com.parknest.api.listings.PricingService;
import com.parknest.api.payments.Payment;
import com.parknest.api.payments.PaymentGateway;
import com.parknest.api.payments.PaymentRepository;
import com.parknest.api.payments.Refund;
import com.parknest.api.payments.RefundRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The driver's side of bookings: hold or request a spot, pay, check in and out, see bookings and cancel.
 * Bays: held, confirmed and checked-in bookings take a bay; requests waiting for the host do not, so several drivers
 * can ask for the same times (the host's approval decides).
 */
@Service
public class BookingService {

    static final Pattern REGISTRATION = Pattern.compile("^[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{1,4}$");
    static final Set<String> VEHICLES = Set.of("HATCHBACK", "SEDAN", "SUV", "BIKE");
    static final Set<String> PAYMENT_METHODS = Set.of("UPI", "CARD", "NETBANKING");
    static final Set<String> CANCELLABLE = Set.of("PENDING_PAYMENT", "PENDING_HOST_APPROVAL", "CONFIRMED");
    static final Set<String> CANCEL_REASONS = Set.of("PLANS_CHANGED", "FOUND_OTHER_SPOT", "HOST_ASKED", "BOOKED_BY_MISTAKE", "OTHER");
    static final int CHECK_IN_EARLY_MINUTES = 30;
    static final String CANCEL_POLICY = "Free cancellation until 24 hours before the start. After that, the first day and the service fee are not refunded.";
    static final String REFUND_ETA = "5–7 working days";

    private final BookingRepository bookings;
    private final ListingRepository listings;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PricingService pricing;
    private final PaymentGateway gateway;
    private final int holdMinutes;

    public BookingService(BookingRepository bookings, ListingRepository listings, PaymentRepository payments, RefundRepository refunds,
                          PricingService pricing, PaymentGateway gateway, @Value("${parknest.bookings.hold-minutes:10}") int holdMinutes) {
        this.bookings = bookings;
        this.listings = listings;
        this.payments = payments;
        this.refunds = refunds;
        this.pricing = pricing;
        this.gateway = gateway;
        this.holdMinutes = holdMinutes;
    }

    // ---------- Booking ----------

    /** POST /bookings: instant spots are held for payment; host-approval spots become a request. */
    @Transactional
    public Hold create(String driverId, BookingDtos.CreateRequest req) {
        expireHolds();
        Listing listing = listings.findForUpdate(req.listingId()).filter(Listing::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found", "NOT_FOUND", null, "This spot is no longer listed"));
        if (listing.getHostId().equals(driverId)) throw ApiException.invalid("OWN_SPOT", "listingId", "You cannot book your own spot");
        if (!VEHICLES.contains(req.vehicle().type())) throw ApiException.invalid("vehicle.type", "Choose your vehicle");
        String reg = registration(req.vehicle().registrationNumber(), false);
        if (req.paymentMethod() != null && !PAYMENT_METHODS.contains(req.paymentMethod())) throw ApiException.invalid("paymentMethod", "Choose UPI, card or net banking");
        PricingService.Stay stay = pricing.stay(req.startAt(), req.endAt());
        PricingService.Price price = pricing.price(listing, stay, req.vehicle().type());
        requireFreeBay(listing, stay.start(), stay.end(), null);

        boolean instant = "INSTANT".equals(listing.getBookingMode());
        Booking b = new Booking();
        b.setId(Ids.newId("bk"));
        b.setBookingRef("PN-" + bookings.nextRef());
        b.setListingId(listing.getId());
        b.setDriverId(driverId);
        b.setHostId(listing.getHostId());
        b.setBookingMode(listing.getBookingMode());
        b.setStatus(instant ? "PENDING_PAYMENT" : "PENDING_HOST_APPROVAL");
        b.setStartAt(stay.start());
        b.setEndAt(stay.end());
        b.setVehicleType(req.vehicle().type());
        b.setVehicleRegistration(reg);
        b.setParkingPaise(price.parkingPaise());
        b.setServiceFeePaise(price.serviceFeePaise());
        b.setGstPaise(price.gstPaise());
        b.setTotalPaise(price.totalPaise());
        b.setHostEarningsPaise(price.parkingPaise());
        b.setPriceLines(price.lines().stream().map(l -> Map.<String, Object>of("code", l.code(), "label", l.label(), "amount", l.amount())).toList());
        if (instant) b.setHoldExpiresAt(OffsetDateTime.now().plusMinutes(holdMinutes));
        bookings.saveAndFlush(b);
        Payment payment = instant ? newPaymentOrder(b, req.paymentMethod()) : null;
        return hold(b, payment);
    }

    /** PATCH /bookings/{id}: sets the vehicle number before payment. Also returns the payment order to finish paying. */
    @Transactional
    public Hold update(String driverId, String id, BookingDtos.UpdateRequest req) {
        expireHolds();
        Booking b = own(driverId, id);
        if (b.is("EXPIRED")) throw holdExpired("Your hold on this spot has ended. Hold it again to continue.");
        if (!b.is("PENDING_PAYMENT", "PENDING_HOST_APPROVAL")) throw ApiException.conflict("INVALID_STATUS", null, "This booking can no longer be changed");
        b.setVehicleRegistration(registration(req.vehicle().registrationNumber(), true));
        Payment payment = null;
        if (b.is("PENDING_PAYMENT")) {
            payment = payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), "CREATED").orElseGet(() -> newPaymentOrder(b, null));
        }
        return hold(b, payment);
    }

    /** POST /payments/verify: the server confirms the payment with the provider, then the booking is confirmed. */
    @Transactional
    public BookingDtos.PaymentVerifyResult verifyPayment(String driverId, BookingDtos.PaymentVerifyRequest req) {
        expireHolds();
        Booking b = bookings.findForUpdate(req.bookingId()).filter(x -> x.getDriverId().equals(driverId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        Payment payment = payments.findByProviderOrderId(req.orderId()).filter(p -> p.getBookingId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (b.is("CONFIRMED", "CHECKED_IN", "COMPLETED") && "CAPTURED".equals(payment.getStatus())) {
            return new BookingDtos.PaymentVerifyResult(b.getId(), b.getBookingRef(), b.getStatus(), Money.ofPaise(payment.getAmountPaise()));
        }
        if (b.is("EXPIRED")) throw holdExpired("Your hold ended before payment finished. You have not been charged.");
        if (!b.is("PENDING_PAYMENT")) throw ApiException.conflict("INVALID_STATUS", null, "This booking is not waiting for payment");
        if (b.getVehicleRegistration() == null) {
            throw ApiException.invalid("REGISTRATION_REQUIRED", "vehicle.registrationNumber", "Add your vehicle number before paying");
        }
        if (!gateway.verify(req.orderId(), req.paymentId(), req.signature())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Payment not confirmed", "PAYMENT_NOT_VERIFIED", null,
                    "We could not confirm this payment. If money was taken, it will be returned automatically.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        payment.setStatus("CAPTURED");
        payment.setProviderPaymentId(req.paymentId());
        payment.setCapturedAt(now);
        if (req.paymentMethod() != null && PAYMENT_METHODS.contains(req.paymentMethod())) payment.setMethod(req.paymentMethod());
        b.setStatus("CONFIRMED");
        b.setConfirmedAt(now);
        b.setHoldExpiresAt(null);
        return new BookingDtos.PaymentVerifyResult(b.getId(), b.getBookingRef(), "CONFIRMED", Money.ofPaise(payment.getAmountPaise()));
    }

    // ---------- Viewing ----------

    /** GET /bookings?tab=upcoming|current|past, with a count per tab. */
    @Transactional
    public Page list(String driverId, String tab) {
        expireHolds();
        String t = tab == null ? "upcoming" : tab;
        if (!Set.of("upcoming", "current", "past").contains(t)) throw ApiException.invalid("tab", "tab must be upcoming, current or past");
        List<Booking> all = bookings.findByDriverIdOrderByStartAtAsc(driverId);
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("upcoming", 0, "current", 0, "past", 0));
        for (Booking b : all) counts.merge(tabOf(b), 1, Integer::sum);
        List<Booking> chosen = new ArrayList<>(all.stream().filter(b -> tabOf(b).equals(t)).toList());
        if (t.equals("past")) chosen.sort(Comparator.comparing(Booking::getStartAt).reversed());
        Map<String, Listing> byId = new LinkedHashMap<>();
        listings.findAllById(chosen.stream().map(Booking::getListingId).distinct().toList()).forEach(l -> byId.put(l.getId(), l));
        List<BookingDtos.Summary> items = chosen.stream().map(b -> summary(b, byId.get(b.getListingId()))).toList();
        return new Page(items, counts);
    }

    public record Page(List<BookingDtos.Summary> items, Map<String, Integer> counts) {}

    /** GET /bookings/{id}. Entry instructions only once the booking is paid. */
    @Transactional
    public BookingDtos.Detail detail(String driverId, String id) {
        expireHolds();
        Booking b = own(driverId, id);
        Listing l = listings.findById(b.getListingId()).orElseThrow(() -> ApiException.notFound("Listing"));
        boolean paid = b.is("CONFIRMED", "CHECKED_IN", "COMPLETED");
        String method = paid ? payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), "CAPTURED").map(Payment::getMethod).orElse(null) : null;
        String settlement = switch (b.getStatus()) {
            case "CONFIRMED", "CHECKED_IN" -> "AFTER_CHECK_OUT";
            case "COMPLETED" -> "PROCESSING";
            default -> "NOT_APPLICABLE";
        };
        boolean cancellable = CANCELLABLE.contains(b.getStatus()) && b.getEndAt().isAfter(OffsetDateTime.now());
        return new BookingDtos.Detail(b.getId(), b.getBookingRef(), b.getStatus(), b.getBookingMode(),
                new BookingDtos.DetailListing(l.getId(), l.getTitle(), l.getBayLabel(),
                        new ListingDtos.Address(l.getLine1(), l.getArea(), l.getCity(), l.getState(), l.getPincode(),
                                new com.parknest.api.common.Geo.Point(l.getLat().doubleValue(), l.getLng().doubleValue()))),
                new BookingDtos.Vehicle(b.getVehicleRegistration(), b.getVehicleType()), Times.ist(b.getStartAt()), Times.ist(b.getEndAt()),
                b.chargedDays(), Money.ofPaise(b.getTotalPaise()), method, paid ? l.getEntryInstructions() : null,
                Times.ist(b.getStartAt().minusMinutes(CHECK_IN_EARLY_MINUTES)), timeline(b), new BookingDtos.Settlement(settlement),
                cancellable ? Times.ist(freeUntil(b, l)) : null, cancellation(b));
    }

    // ---------- Check-in and check-out ----------

    /** POST /bookings/{id}/check-in: from 30 minutes before the start until the end. */
    @Transactional
    public BookingDtos.StatusChange checkIn(String driverId, String id) {
        Booking b = own(driverId, id);
        if (!b.is("CONFIRMED")) throw ApiException.conflict("INVALID_STATUS", null, "This booking cannot be checked in");
        OffsetDateTime now = OffsetDateTime.now();
        if (now.isBefore(b.getStartAt().minusMinutes(CHECK_IN_EARLY_MINUTES))) {
            throw ApiException.invalid("CHECK_IN_TOO_EARLY", null, "Check-in opens 30 minutes before your start time");
        }
        if (now.isAfter(b.getEndAt())) throw ApiException.conflict("BOOKING_ENDED", null, "This booking has already ended");
        b.setStatus("CHECKED_IN");
        b.setCheckedInAt(now);
        return new BookingDtos.StatusChange(b.getId(), b.getStatus(), Times.ist(now), null);
    }

    /** POST /bookings/{id}/check-out: completes the booking; the host is paid after this. */
    @Transactional
    public BookingDtos.StatusChange checkOut(String driverId, String id) {
        Booking b = own(driverId, id);
        if (!b.is("CHECKED_IN")) throw ApiException.conflict("INVALID_STATUS", null, "Check in before checking out");
        OffsetDateTime now = OffsetDateTime.now();
        b.setStatus("COMPLETED");
        b.setCheckedOutAt(now);
        return new BookingDtos.StatusChange(b.getId(), b.getStatus(), null, Times.ist(now));
    }

    // ---------- Cancelling ----------

    /** GET /bookings/{id}/cancellation: what cancelling now would refund. */
    @Transactional
    public BookingDtos.CancellationPreview cancellationPreview(String driverId, String id) {
        expireHolds();
        Booking b = own(driverId, id);
        return preview(b, listings.findById(b.getListingId()).orElseThrow());
    }

    /** POST /bookings/{id}/cancel */
    @Transactional
    public BookingDtos.CancelResult cancel(String driverId, String id, BookingDtos.CancelRequest req) {
        if (req == null || !CANCEL_REASONS.contains(req.reason())) throw ApiException.invalid("reason", "Choose a reason for cancelling");
        expireHolds();
        Booking b = bookings.findForUpdate(id).filter(x -> x.getDriverId().equals(driverId)).orElseThrow(() -> ApiException.notFound("Booking"));
        Listing l = listings.findById(b.getListingId()).orElseThrow();
        BookingDtos.CancellationPreview p = preview(b, l);
        if (!p.canCancel()) throw ApiException.conflict("CANCEL_NOT_ALLOWED", null, p.reason());
        long refundPaise = p.refund().amount() * 100;
        String note = req.note() == null || req.note().isBlank() ? "" : ": " + req.note().trim();
        Refund refund = cancelAndRefund(b, "DRIVER", req.reason() + note, refundPaise);
        if (b.getConfirmedAt() != null) {
            // The host keeps the part that is not refunded (the first day's parking), never more than the parking amount.
            long kept = b.getTotalPaise() - refundPaise;
            b.setHostEarningsPaise(Math.max(0, Math.min(b.getParkingPaise(), kept - b.getServiceFeePaise() - b.getGstPaise())));
        }
        return new BookingDtos.CancelResult(b.getId(), "CANCELLED", Times.ist(b.getCancelledAt()), Money.ofPaise(refundPaise),
                refund != null ? "PROCESSING" : "NOT_APPLICABLE", refund != null ? REFUND_ETA : null);
    }

    /**
     * Cancels and, if money was taken, refunds refundPaise through the provider. Used by drivers here and by ParkNest
     * (admin). Returns the refund, or null when nothing was paid.
     */
    @Transactional
    public Refund cancelAndRefund(Booking b, String by, String reason, long refundPaise) {
        OffsetDateTime now = OffsetDateTime.now();
        b.setStatus("CANCELLED");
        b.setCancelledAt(now);
        b.setCancelledBy(by);
        b.setCancellationReason(reason);
        b.setHoldExpiresAt(null);
        if (b.getConfirmedAt() == null) b.setHostEarningsPaise(0);
        if (refundPaise <= 0) return null;
        Payment paid = payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), "CAPTURED")
                .orElseThrow(() -> new IllegalStateException("Paid booking without a captured payment: " + b.getId()));
        Refund r = new Refund();
        r.setId(Ids.newId("rf"));
        r.setPaymentId(paid.getId());
        r.setBookingId(b.getId());
        r.setAmountPaise(refundPaise);
        r.setReason(reason);
        r.setInitiatedBy(by);
        try {
            r.setProviderRefundId(gateway.refund(paid.getProviderPaymentId(), refundPaise));
        } catch (RuntimeException e) {
            r.setStatus("FAILED");
            r.setFailureReason("The payment provider did not accept the refund: " + e.getMessage());
        }
        return refunds.save(r);
    }

    BookingDtos.CancellationPreview preview(Booking b, Listing l) {
        Money zero = new Money("INR", 0);
        String freeUntil = Times.ist(freeUntil(b, l));
        boolean ended = !b.getEndAt().isAfter(OffsetDateTime.now());
        if (!CANCELLABLE.contains(b.getStatus()) || ended) {
            String reason = b.is("CHECKED_IN") ? "You are checked in, so this booking can no longer be cancelled. Check out when you leave."
                    : b.is("CANCELLED") ? "This booking is already cancelled."
                    : b.is("DECLINED") ? "The host declined this request."
                    : b.is("EXPIRED") ? "This hold has already ended."
                    : "This booking has ended.";
            return new BookingDtos.CancellationPreview(b.getId(), false, reason, freeUntil, false, Money.ofPaise(b.getTotalPaise()), zero, zero, null, null, CANCEL_POLICY);
        }
        if (!b.is("CONFIRMED")) {
            // Requests and unpaid holds: nothing was charged.
            return new BookingDtos.CancellationPreview(b.getId(), true, null, freeUntil, true, zero, zero, zero, null, null, CANCEL_POLICY);
        }
        boolean free = OffsetDateTime.now().isBefore(freeUntil(b, l));
        long keep = 0;
        if (!free) {
            long hours = (Duration.between(b.getStartAt(), b.getEndAt()).toMinutes() + 59) / 60;
            long firstDay = hours < 24 ? b.getParkingPaise() : l.getPricePerDayPaise();
            keep = Math.min(b.getTotalPaise(), firstDay + b.getServiceFeePaise() + b.getGstPaise());
        }
        long refund = b.getTotalPaise() - keep;
        String method = payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), "CAPTURED").map(Payment::getMethod).orElse("UPI");
        String to = "CARD".equals(method) ? "card" : "NETBANKING".equals(method) ? "bank account" : "UPI";
        return new BookingDtos.CancellationPreview(b.getId(), true, null, freeUntil, free, Money.ofPaise(b.getTotalPaise()), Money.ofPaise(refund),
                Money.ofPaise(keep), refund > 0 ? to : null, refund > 0 ? REFUND_ETA : null, CANCEL_POLICY);
    }

    // ---------- Shared ----------

    /** Throws 409 SPOT_UNAVAILABLE when every bay is taken at some moment in [start, end). */
    void requireFreeBay(Listing listing, OffsetDateTime start, OffsetDateTime end, String ignoreBookingId) {
        if (bookings.peakOccupancy(listing.getId(), start, end, ignoreBookingId) >= listing.getBays()) {
            throw ApiException.conflict("SPOT_UNAVAILABLE", null, listing.getBays() > 1
                    ? "All bays are booked for these times. Try other times." : "This spot is already booked for these times. Try other times.");
        }
    }

    /** Marks unpaid holds whose time ran out as EXPIRED (also done every minute by BookingJobs). */
    public void expireHolds() {
        bookings.expireHolds(OffsetDateTime.now());
    }

    private Payment newPaymentOrder(Booking b, String method) {
        PaymentGateway.Order order = gateway.createOrder(b.getBookingRef(), b.getTotalPaise());
        Payment p = new Payment();
        p.setId(Ids.newId("pay"));
        p.setBookingId(b.getId());
        p.setProvider(gateway.provider());
        p.setProviderOrderId(order.orderId());
        p.setAmountPaise(b.getTotalPaise());
        p.setMethod(method);
        p.setProviderPayload(order.clientToken() == null ? null : Map.of("clientToken", order.clientToken()));
        return payments.save(p);
    }

    private Hold hold(Booking b, Payment payment) {
        BookingDtos.PaymentOrder order = payment == null ? null : new BookingDtos.PaymentOrder(payment.getProvider(), payment.getProviderOrderId(),
                payment.getProviderPayload() == null ? null : (String) payment.getProviderPayload().get("clientToken"), List.copyOf(PAYMENT_METHODS_ORDERED));
        return new Hold(b.getId(), b.getBookingRef(), b.getStatus(), b.getBookingMode(), Times.ist(b.getHoldExpiresAt()), Money.ofPaise(b.getTotalPaise()), order);
    }

    private static final List<String> PAYMENT_METHODS_ORDERED = List.of("UPI", "CARD", "NETBANKING");

    private Booking own(String driverId, String id) {
        return bookings.findByIdAndDriverId(id, driverId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found", "NOT_FOUND", null, "We could not find this booking"));
    }

    static String registration(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) throw ApiException.invalid("INVALID_REGISTRATION", "vehicle.registrationNumber", "Enter a valid vehicle number, e.g. KA 01 AB 1234");
            return null;
        }
        String reg = raw.replaceAll("[\\s-]", "").toUpperCase();
        if (!REGISTRATION.matcher(reg).matches()) {
            throw ApiException.invalid("INVALID_REGISTRATION", "vehicle.registrationNumber", "Enter a valid vehicle number, e.g. KA 01 AB 1234");
        }
        return reg;
    }

    static String tabOf(Booking b) {
        if (b.is("CHECKED_IN")) return "current";
        if (b.is("PENDING_PAYMENT", "PENDING_HOST_APPROVAL", "CONFIRMED") && b.getEndAt().isAfter(OffsetDateTime.now())) return "upcoming";
        return "past";
    }

    static OffsetDateTime freeUntil(Booking b, Listing l) {
        return b.getStartAt().minusHours(l.getCancellationHours());
    }

    static Cancellation cancellation(Booking b) {
        if (!b.is("CANCELLED")) return null;
        return new Cancellation(Times.ist(b.getCancelledAt()), Money.ofPaise(b.getTotalPaise() - keptPaise(b)), refundStatusOf(b));
    }

    /** What was not refunded: everything for unpaid bookings is 0 refund. */
    private static long keptPaise(Booking b) {
        if (b.getConfirmedAt() == null) return b.getTotalPaise();
        return b.getHostEarningsPaise() + (b.getHostEarningsPaise() > 0 ? b.getServiceFeePaise() + b.getGstPaise() : 0);
    }

    private static String refundStatusOf(Booking b) {
        return b.getConfirmedAt() == null ? "NOT_APPLICABLE" : "PROCESSING";
    }

    private BookingDtos.Summary summary(Booking b, Listing l) {
        return new BookingDtos.Summary(b.getId(), b.getBookingRef(), b.getStatus(), b.getBookingMode(),
                new BookingDtos.SummaryListing(b.getListingId(), l == null ? "Parking spot" : l.getTitle(), l == null ? "" : l.getArea(),
                        l == null ? null : FileUrls.of(l.coverFileId())),
                new BookingDtos.Vehicle(b.getVehicleRegistration(), b.getVehicleType()), Times.ist(b.getStartAt()), Times.ist(b.getEndAt()),
                b.chargedDays(), Money.ofPaise(b.getTotalPaise()), b.is("PENDING_PAYMENT") ? Times.ist(b.getHoldExpiresAt()) : null,
                Times.ist(b.getCheckedInAt()), Times.ist(b.getCheckedOutAt()), cancellation(b));
    }

    private static List<BookingDtos.TimelineStep> timeline(Booking b) {
        String in = Times.ist(b.getCheckedInAt());
        String out = Times.ist(b.getCheckedOutAt());
        return switch (b.getStatus()) {
            case "CHECKED_IN" -> List.of(step("CHECK_IN", "Check in", "DONE", in), step("PARK", "Park", "CURRENT", in), step("CHECK_OUT", "Check out", "PENDING", null));
            case "COMPLETED" -> List.of(step("CHECK_IN", "Check in", "DONE", in), step("PARK", "Park", "DONE", null), step("CHECK_OUT", "Check out", "DONE", out));
            case "CONFIRMED" -> List.of(step("CHECK_IN", "Check in", "CURRENT", null), step("PARK", "Park", "PENDING", null), step("CHECK_OUT", "Check out", "PENDING", null));
            default -> List.of(step("CHECK_IN", "Check in", "PENDING", null), step("PARK", "Park", "PENDING", null), step("CHECK_OUT", "Check out", "PENDING", null));
        };
    }

    private static BookingDtos.TimelineStep step(String code, String label, String status, String at) {
        return new BookingDtos.TimelineStep(code, label, status, at);
    }

    private static ApiException holdExpired(String message) {
        return ApiException.conflict("HOLD_EXPIRED", null, message);
    }
}
