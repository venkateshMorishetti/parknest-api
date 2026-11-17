package com.parknest.api.listings;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Geo;
import com.parknest.api.common.Ids;
import com.parknest.api.common.Times;
import com.parknest.api.files.FileUrls;
import com.parknest.api.listings.ListingDtos.ListingDetail;
import com.parknest.api.listings.ListingDtos.ListingSummary;
import com.parknest.api.locations.PlaceService;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Search, spot pages and quotes for drivers (no login needed). Only ACTIVE spots are visible. */
@Service
public class ListingService {

    /** Without "Search this area", spots within this distance of the searched place. */
    static final double SEARCH_RADIUS_KM = 5.0;
    private static final Set<String> VEHICLES = Set.of("HATCHBACK", "SEDAN", "SUV", "BIKE");
    private static final Set<String> SPOT_TYPES = Set.of("COVERED", "OPEN", "BASEMENT");
    private static final Map<String, String> SPOT_WORDS = Map.of("COVERED", "A covered bay", "OPEN", "An open bay", "BASEMENT", "A basement bay");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ListingRepository listings;
    private final PricingService pricing;
    private final PlaceService places;

    public ListingService(ListingRepository listings, PricingService pricing, PlaceService places) {
        this.listings = listings;
        this.pricing = pricing;
        this.places = places;
    }

    public record SearchPage(List<ListingSummary> items, Map<String, Object> meta) {}

    @Transactional(readOnly = true)
    public SearchPage search(ListingDtos.SearchRequest req) {
        PricingService.Stay stay = pricing.stay(req.startAt(), req.endAt());
        String vehicle = blank(req.vehicleType()) ? null : req.vehicleType();
        if (vehicle != null && !VEHICLES.contains(vehicle)) throw ApiException.invalid("vehicleType", "Choose a vehicle type");
        String spot = blank(req.spotType()) || "ANY".equals(req.spotType()) ? null : req.spotType();
        if (spot != null && !SPOT_TYPES.contains(spot)) throw ApiException.invalid("spotType", "Choose a spot type");

        Geo.Bounds area = req.area();
        if (area != null && (area.north() <= area.south() || area.east() <= area.west())) throw ApiException.invalid("area", "The map area is not valid");
        Geo.Point origin = req.location().geo() != null ? req.location().geo()
                : places.byLabel(req.location().label()).map(PlaceService.Place::geo)
                .orElseGet(() -> area == null ? null : new Geo.Point((area.north() + area.south()) / 2, (area.east() + area.west()) / 2));
        if (origin == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "LOCATION_NOT_FOUND", "location",
                    "We could not find that place. Choose one from the list or use your location.");
        }
        Geo.Bounds box = area != null ? area : around(origin, SEARCH_RADIUS_KM);

        ListingDtos.SearchFilters f = req.filters();
        Long maxPrice = f != null && f.maxPricePerDay() != null && f.maxPricePerDay() > 0 ? f.maxPricePerDay() * 100 : null;
        SearchCriteria criteria = new SearchCriteria(box, stay.start(), stay.end(), vehicle, spot,
                f != null && f.instantBookOnly(), f != null && f.coveredOnly(), f != null && f.cctv(), maxPrice);
        List<Listing> matches = listings.findWithHostByIdIn(listings.searchIds(criteria));

        List<ListingSummary> found = new ArrayList<>();
        for (Listing row : matches) {
            double km = Geo.distanceKm(origin.lat(), origin.lng(), row.getLat().doubleValue(), row.getLng().doubleValue());
            if (area == null && km > SEARCH_RADIUS_KM) continue;
            found.add(summary(row, Geo.round1(km)));
        }
        Comparator<ListingSummary> nearest = Comparator.comparingDouble(ListingSummary::distanceKm);
        Comparator<ListingSummary> order = switch (req.sort() == null ? "NEAREST" : req.sort()) {
            case "PRICE_LOW" -> Comparator.comparingLong((ListingSummary l) -> l.pricing().perDay()).thenComparing(nearest);
            case "PRICE_HIGH" -> Comparator.comparingLong((ListingSummary l) -> -l.pricing().perDay()).thenComparing(nearest);
            default -> nearest;
        };
        found.sort(order);

        int page = req.page() == null ? 1 : req.page();
        int size = req.pageSize() == null ? 20 : req.pageSize();
        int total = found.size();
        int pages = Math.max(1, (total + size - 1) / size);
        List<ListingSummary> items = found.subList(Math.min(total, (page - 1) * size), Math.min(total, page * size));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pagination", Map.of("page", page, "pageSize", size, "totalItems", total, "totalPages", pages,
                "hasNext", page < pages, "hasPrevious", page > 1));
        meta.put("searchId", Ids.newId("srch"));
        meta.put("mapBounds", bounds(found));
        return new SearchPage(items, meta);
    }

    /** GET /listings/:id. lat/lng (optional) give distanceKm from the searched place. */
    @Transactional(readOnly = true)
    public ListingDetail detail(String id, Double lat, Double lng) {
        Listing row = active(id);
        Double km = lat != null && lng != null ? Geo.round1(Geo.distanceKm(lat, lng, row.getLat().doubleValue(), row.getLng().doubleValue())) : null;
        List<ListingDtos.Photo> photos = new ArrayList<>();
        for (int i = 0; i < row.getPhotos().size(); i++) {
            ListingPhoto p = row.getPhotos().get(i);
            photos.add(new ListingDtos.Photo(p.getFileId(), FileUrls.of(p.getFileId()), p.getCaption() != null ? p.getCaption() : "Photo " + (i + 1)));
        }
        String description = row.getDescription() != null && !row.getDescription().isBlank() ? row.getDescription()
                : SPOT_WORDS.get(row.getSpotType()) + " at " + row.getLine1() + ", " + row.getArea() + ". Entry details are shared after booking.";
        int hours = row.getCancellationHours();
        return new ListingDetail(row.getId(), row.getTitle(), address(row), km, row.getSpotType(), row.getBays(), List.copyOf(row.getVehicleTypes()),
                pricingOf(row), row.getBookingMode(), List.copyOf(row.getSecurity()), FileUrls.of(row.coverFileId()), row.getBayLabel(), description, photos,
                new ListingDtos.DetailAccessHours(row.is24x7(), "MON-SUN", time(row.getOpensAt()), time(row.getClosesAt())),
                new ListingDtos.CancellationPolicy("FREE_UNTIL", hours, "Free cancellation until " + hours + " hours before start"),
                new ListingDtos.DetailHost(row.getHostId(), row.getHost().getName(), true, null, row.getHost().getCreatedAt().toLocalDate().toString()),
                List.of());
    }

    /** POST /listings/:id/quote. Prices are not stored; the booking recalculates them. */
    @Transactional(readOnly = true)
    public ListingDtos.Quote quote(String id, ListingDtos.QuoteRequest req) {
        Listing row = active(id);
        PricingService.Stay stay = pricing.stay(req.startAt(), req.endAt());
        PricingService.Price p = pricing.price(row, stay, req.vehicleType());
        return new ListingDtos.Quote(Ids.newId("qt"), row.getId(), "INR", p.chargedDays(), p.hours(), row.getBookingMode(), p.lines(),
                p.totalPaise() / 100, Times.ist(OffsetDateTime.now().plusMinutes(15)));
    }

    Listing active(String id) {
        return listings.findWithHost(id).filter(Listing::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found", "NOT_FOUND", null, "This spot is no longer listed"));
    }

    static ListingSummary summary(Listing row, Double km) {
        return new ListingSummary(row.getId(), row.getTitle(), address(row), km, row.getSpotType(), row.getBays(), List.copyOf(row.getVehicleTypes()),
                pricingOf(row), row.getBookingMode(), List.copyOf(row.getSecurity()),
                new ListingDtos.AccessHours(row.is24x7(), time(row.getOpensAt()), time(row.getClosesAt())),
                FileUrls.of(row.coverFileId()), new ListingDtos.HostSummary(row.getHostId(), row.getHost().getName(), true));
    }

    static ListingDtos.Address address(Listing row) {
        return new ListingDtos.Address(row.getLine1(), row.getArea(), row.getCity(), row.getState(), row.getPincode(),
                new Geo.Point(row.getLat().doubleValue(), row.getLng().doubleValue()));
    }

    static ListingDtos.Pricing pricingOf(Listing row) {
        return new ListingDtos.Pricing("INR", row.getPricePerDayPaise() / 100, row.getPricePerHourPaise() / 100);
    }

    static String time(java.time.LocalTime t) {
        return t == null ? null : t.format(HH_MM);
    }

    /** A square box around a point (for the index), then trimmed to a circle in code. */
    static Geo.Bounds around(Geo.Point p, double km) {
        double dLat = km / 111.0;
        double dLng = km / (111.0 * Math.cos(Math.toRadians(p.lat())));
        return new Geo.Bounds(p.lat() + dLat, p.lat() - dLat, p.lng() + dLng, p.lng() - dLng);
    }

    /** The map area that shows every result, or null when there are none. */
    static Geo.Bounds bounds(List<ListingSummary> items) {
        if (items.isEmpty()) return null;
        double n = -90, s = 90, e = -180, w = 180;
        for (ListingSummary l : items) {
            n = Math.max(n, l.address().geo().lat());
            s = Math.min(s, l.address().geo().lat());
            e = Math.max(e, l.address().geo().lng());
            w = Math.min(w, l.address().geo().lng());
        }
        return new Geo.Bounds(n, s, e, w);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
