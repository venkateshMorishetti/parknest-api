package com.parknest.api.hosting;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Geo;
import com.parknest.api.common.Ids;
import com.parknest.api.common.Responses;
import com.parknest.api.common.Times;
import com.parknest.api.files.FileUrls;
import com.parknest.api.files.StoredFile;
import com.parknest.api.files.StoredFileRepository;
import com.parknest.api.hosting.HostDtos.ListingRequest;
import com.parknest.api.listings.Listing;
import com.parknest.api.listings.ListingPhoto;
import com.parknest.api.listings.ListingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A host's own spots: add, list, edit, pause and resume. New spots, moved spots and spots edited after a rejection go
 * to ParkNest for review (NEEDS_APPROVAL) before drivers can book them.
 */
@Service
public class HostListingService {

    static final String REVIEW_ESTIMATE = "Usually within a few hours";
    private static final Set<String> SPOT_TYPES = Set.of("COVERED", "OPEN", "BASEMENT");
    private static final Set<String> VEHICLES = Set.of("HATCHBACK", "SEDAN", "SUV", "BIKE");
    private static final Set<String> SECURITY = Set.of("CCTV", "GATED", "SECURITY_GUARD", "WELL_LIT");
    private static final Set<String> MODES = Set.of("INSTANT", "HOST_APPROVAL");
    /** About 20 m: a pin moved further than this needs a new review. */
    private static final double MOVE_KM = 0.02;

    private final ListingRepository listings;
    private final StoredFileRepository files;
    private final HostingService hosting;

    public HostListingService(ListingRepository listings, StoredFileRepository files, HostingService hosting) {
        this.listings = listings;
        this.files = files;
        this.hosting = hosting;
    }

    /** POST /host/listings */
    @Transactional
    public HostDtos.CreatedListing create(String hostId, ListingRequest req) {
        hosting.requireHost(hostId);
        Listing l = new Listing();
        l.setId(Ids.newId("lst"));
        l.setHostId(hostId);
        apply(l, req);
        l.setPhotos(photos(hostId, l.getId(), req.photoFileIds()));
        OffsetDateTime now = OffsetDateTime.now();
        l.setStatus("NEEDS_APPROVAL");
        l.setReviewReason("NEW");
        l.setSubmittedAt(now);
        listings.saveAndFlush(l);
        return new HostDtos.CreatedListing(l.getId(), l.getStatus(), Times.ist(now), REVIEW_ESTIMATE);
    }

    /** GET /host/listings?status=ALL|ACTIVE|NEEDS_APPROVAL|PAUSED|REJECTED, with a count per status. */
    @Transactional(readOnly = true)
    public Page list(String hostId, String filter) {
        hosting.requireHost(hostId);
        String status = filter == null || filter.isBlank() ? "ALL" : filter;
        if (!Set.of("ALL", "ACTIVE", "NEEDS_APPROVAL", "PAUSED", "REJECTED").contains(status)) {
            throw ApiException.invalid("status", "status must be ALL, ACTIVE, NEEDS_APPROVAL, PAUSED or REJECTED");
        }
        List<Listing> all = listings.findByHostIdAndDeletedAtIsNullOrderByCreatedAtDesc(hostId);
        OffsetDateTime monthStart = ZonedDateTime.now(Responses.IST).withDayOfMonth(1).toLocalDate().atStartOfDay(Responses.IST).toOffsetDateTime();
        Map<String, Integer> bookings = new HashMap<>();
        for (Object[] row : listings.bookingsSince(hostId, monthStart)) bookings.put((String) row[0], ((Number) row[1]).intValue());

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("ALL", "ACTIVE", "NEEDS_APPROVAL", "PAUSED", "REJECTED")) {
            counts.put(s, (int) all.stream().filter(l -> s.equals("ALL") || s.equals(l.getStatus())).count());
        }
        List<HostDtos.ManagedListing> items = all.stream()
                .filter(l -> status.equals("ALL") || status.equals(l.getStatus()))
                .map(l -> managed(l, bookings.getOrDefault(l.getId(), 0)))
                .toList();
        return new Page(items, counts);
    }

    public record Page(List<HostDtos.ManagedListing> items, Map<String, Integer> counts) {}

    /** GET /host/listings/{id}: the edit form, including entry instructions. */
    @Transactional(readOnly = true)
    public HostDtos.EditableListing get(String hostId, String id) {
        hosting.requireHost(hostId);
        Listing l = own(hostId, id);
        return new HostDtos.EditableListing(l.getId(), l.getTitle(), address(l), l.getBayLabel(), l.getEntryInstructions(), l.getSpotType(),
                l.getBays(), List.copyOf(l.getVehicleTypes()), List.copyOf(l.getSecurity()), pricing(l), hours(l), l.getBookingMode(),
                l.getStatus(), l.getPhotos().stream().map(p -> new HostDtos.EditablePhoto(p.getFileId(), FileUrls.of(p.getFileId()))).toList(),
                Times.ist(l.getUpdatedAt()));
    }

    /** PUT /host/listings/{id}. A moved address or pin sends a live spot back to review, as does editing a rejected one. */
    @Transactional
    public HostDtos.UpdatedListing update(String hostId, String id, ListingRequest req) {
        hosting.requireHost(hostId);
        Listing l = own(hostId, id);
        String before = l.getStatus();
        String oldLine1 = l.getLine1();
        String oldPincode = l.getPincode();
        double oldLat = l.getLat().doubleValue();
        double oldLng = l.getLng().doubleValue();
        apply(l, req);
        l.getPhotos().clear();
        listings.saveAndFlush(l);  // old photo rows go first, so the same files can be added back
        l.getPhotos().addAll(photos(hostId, l.getId(), req.photoFileIds()));

        boolean moved = !oldLine1.equals(l.getLine1()) || !oldPincode.equals(l.getPincode())
                || Geo.distanceKm(oldLat, oldLng, l.getLat().doubleValue(), l.getLng().doubleValue()) > MOVE_KM;
        String reason = null;
        if ("REJECTED".equals(before)) reason = "RESUBMITTED";
        else if (moved && ("ACTIVE".equals(before) || "PAUSED".equals(before))) reason = "LOCATION_CHANGED";
        if (reason != null) {
            l.setStatus("NEEDS_APPROVAL");
            l.setReviewReason(reason);
            l.setReviewNote(null);
            l.setSubmittedAt(OffsetDateTime.now());
        }
        listings.saveAndFlush(l);
        return new HostDtos.UpdatedListing(l.getId(), l.getStatus(), Times.ist(l.getUpdatedAt()), reason != null ? REVIEW_ESTIMATE : null);
    }

    /** PATCH /host/listings/{id}/status: only ACTIVE <-> PAUSED. */
    @Transactional
    public HostDtos.StatusResult setStatus(String hostId, String id, String next) {
        hosting.requireHost(hostId);
        Listing l = own(hostId, id);
        boolean allowed = ("ACTIVE".equals(l.getStatus()) && "PAUSED".equals(next)) || ("PAUSED".equals(l.getStatus()) && "ACTIVE".equals(next));
        if (!allowed) {
            throw ApiException.conflict("INVALID_STATUS", "status", "NEEDS_APPROVAL".equals(l.getStatus())
                    ? "This spot is still being reviewed" : "Only active spots can be paused, and only paused spots resumed");
        }
        l.setStatus(next);
        listings.saveAndFlush(l);
        return new HostDtos.StatusResult(l.getId(), l.getStatus(), Times.ist(l.getUpdatedAt()));
    }

    private Listing own(String hostId, String id) {
        return listings.findByIdAndHostIdAndDeletedAtIsNull(id, hostId).orElseThrow(() -> ApiException.notFound("Listing"));
    }

    /** Checks every field (same rules and messages as the app's form) and copies them onto the spot. */
    static void apply(Listing l, ListingRequest req) {
        String title = trim(req.title());
        if (title.length() < 3 || title.length() > 60) throw ApiException.invalid("title", "Give your spot a name (3 to 60 characters)");
        HostDtos.ListingAddress a = req.address();
        if (a == null) throw ApiException.invalid("address", "Add the address");
        if (trim(a.line1()).length() < 3) throw ApiException.invalid("address.line1", "Enter the house or building and street");
        if (trim(a.area()).isEmpty()) throw ApiException.invalid("address.area", "Enter the area");
        if (trim(a.city()).isEmpty()) throw ApiException.invalid("address.city", "Enter the city");
        if (trim(a.state()).isEmpty()) throw ApiException.invalid("address.state", "Enter the state");
        if (a.pincode() == null || !a.pincode().matches("[1-9]\\d{5}")) throw ApiException.invalid("address.pincode", "Enter a 6-digit pincode");
        if (a.geo() == null || a.geo().lat() == null || a.geo().lng() == null) throw ApiException.invalid("address.geo", "Place the pin on your spot");
        if (!Geo.inIndia(a.geo().lat(), a.geo().lng())) throw ApiException.invalid("address.geo", "Place the pin on your spot in India");
        String entry = trim(req.entryInstructions());
        if (entry.length() < 10 || entry.length() > 300) throw ApiException.invalid("entryInstructions", "Tell drivers how to get in (10 to 300 characters)");
        if (req.bayLabel() != null && req.bayLabel().length() > 60) throw ApiException.invalid("bayLabel", "Keep the bay label under 60 characters");
        if (!SPOT_TYPES.contains(req.spotType())) throw ApiException.invalid("spotType", "Choose the spot type");
        if (req.bays() == null || req.bays() < 1 || req.bays() > 50) throw ApiException.invalid("bays", "Enter 1 to 50 bays");
        List<String> vehicles = req.vehicleTypes() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.vehicleTypes()));
        if (vehicles.isEmpty() || !VEHICLES.containsAll(vehicles)) throw ApiException.invalid("vehicleTypes", "Choose the largest vehicle that fits");
        List<String> security = req.security() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.security()));
        if (!SECURITY.containsAll(security)) throw ApiException.invalid("security", "Unknown security feature");
        HostDtos.Pricing p = req.pricing();
        if (p == null || p.perDay() == null || p.perDay() < 20 || p.perDay() > 5000) throw ApiException.invalid("pricing.perDay", "Enter a daily price from ₹20 to ₹5,000");
        if (p.perHour() == null || p.perHour() < 5 || p.perHour() > 1000) throw ApiException.invalid("pricing.perHour", "Enter an hourly price from ₹5 to ₹1,000");
        if (p.perHour() > p.perDay()) throw ApiException.invalid("pricing.perHour", "The hourly price cannot be more than the daily price");
        HostDtos.AccessHours h = req.accessHours();
        boolean allDay = h == null || h.is24x7() == null || h.is24x7();
        LocalTime opens = null;
        LocalTime closes = null;
        if (!allDay) {
            try {
                opens = LocalTime.parse(h.opensAt());
                closes = LocalTime.parse(h.closesAt());
            } catch (DateTimeParseException | NullPointerException e) {
                throw ApiException.invalid("accessHours", "Enter opening and closing times like 07:00");
            }
            if (opens.equals(closes)) throw ApiException.invalid("accessHours.closesAt", "Closing time must differ from opening time");
        }
        if (!MODES.contains(req.bookingMode())) throw ApiException.invalid("bookingMode", "Choose instant booking or host approval");
        int photos = req.photoFileIds() == null ? 0 : req.photoFileIds().size();
        if (photos == 0) throw ApiException.invalid("photoFileIds", "Add at least one photo");
        if (photos > 8) throw ApiException.invalid("photoFileIds", "Add up to 8 photos");

        l.setTitle(title);
        l.setLine1(trim(a.line1()));
        l.setArea(trim(a.area()));
        l.setCity(trim(a.city()));
        l.setState(trim(a.state()));
        l.setPincode(a.pincode());
        l.setLat(BigDecimal.valueOf(a.geo().lat()).setScale(6, RoundingMode.HALF_UP));
        l.setLng(BigDecimal.valueOf(a.geo().lng()).setScale(6, RoundingMode.HALF_UP));
        l.setBayLabel(req.bayLabel() == null || req.bayLabel().isBlank() ? null : req.bayLabel().trim());
        l.setEntryInstructions(entry);
        l.setSpotType(req.spotType());
        l.setBays(req.bays().shortValue());
        l.setVehicleTypes(new ArrayList<>(vehicles));
        l.setSecurity(new ArrayList<>(security));
        l.setPricePerDayPaise(p.perDay() * 100);
        l.setPricePerHourPaise(p.perHour() * 100);
        l.set24x7(allDay);
        l.setOpensAt(opens);
        l.setClosesAt(closes);
        l.setBookingMode(req.bookingMode());
    }

    /** The photos must be the host's own uploads, not used by another spot. */
    private List<ListingPhoto> photos(String hostId, String listingId, List<String> fileIds) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(fileIds));
        List<StoredFile> owned = files.findByIdInAndOwnerIdAndDeletedAtIsNull(ids, hostId);
        if (owned.size() != ids.size() || owned.stream().anyMatch(f -> !"LISTING_PHOTO".equals(f.getPurpose()))) {
            throw ApiException.invalid("photoFileIds", "Upload the photos again");
        }
        if (!listings.fileIdsUsedElsewhere(ids, listingId).isEmpty()) throw ApiException.invalid("photoFileIds", "A photo is already used by another spot");
        List<ListingPhoto> photos = new ArrayList<>();
        for (String id : ids) photos.add(new ListingPhoto(id, null));
        return photos;
    }

    static HostDtos.ManagedListing managed(Listing l, int bookingsThisMonth) {
        return new HostDtos.ManagedListing(l.getId(), l.getTitle(), address(l), l.getBayLabel(), l.getSpotType(), l.getBays(),
                List.copyOf(l.getVehicleTypes()), pricing(l), l.getBookingMode(), hours(l), l.getStatus(), bookingsThisMonth,
                l.getPhotos().size(), FileUrls.of(l.coverFileId()), Times.ist(l.getUpdatedAt()),
                "REJECTED".equals(l.getStatus()) ? l.getReviewNote() : null);
    }

    static HostDtos.ListingAddress address(Listing l) {
        return new HostDtos.ListingAddress(l.getLine1(), l.getArea(), l.getCity(), l.getState(), l.getPincode(),
                new HostDtos.Geo(l.getLat().doubleValue(), l.getLng().doubleValue()));
    }

    static HostDtos.Pricing pricing(Listing l) {
        return new HostDtos.Pricing("INR", l.getPricePerDayPaise() / 100, l.getPricePerHourPaise() / 100);
    }

    static HostDtos.AccessHours hours(Listing l) {
        return new HostDtos.AccessHours(l.is24x7(), l.getOpensAt() == null ? null : l.getOpensAt().toString(),
                l.getClosesAt() == null ? null : l.getClosesAt().toString());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
