package com.parknest.api.listings;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native SQL for search: uses the (lat, lng) index for the map box, PostgreSQL array checks for vehicle types and
 * security, and listing_peak_occupancy() so fully booked spots are left out.
 */
class ListingSearchImpl implements ListingSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public List<String> searchIds(SearchCriteria c) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.id FROM listings l
                WHERE l.status = 'ACTIVE' AND l.deleted_at IS NULL
                  AND l.lat BETWEEN :south AND :north AND l.lng BETWEEN :west AND :east
                  AND listing_peak_occupancy(l.id, :start, :end) < l.bays
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("south", c.box().south());
        params.put("north", c.box().north());
        params.put("west", c.box().west());
        params.put("east", c.box().east());
        params.put("start", c.start());
        params.put("end", c.end());
        if (c.vehicleType() != null) {
            sql.append(" AND CAST(:vehicle AS text) = ANY(l.vehicle_types)");
            params.put("vehicle", c.vehicleType());
        }
        if (c.spotType() != null) {
            sql.append(" AND l.spot_type = :spot");
            params.put("spot", c.spotType());
        }
        if (c.instantOnly()) sql.append(" AND l.booking_mode = 'INSTANT'");
        if (c.coveredOnly()) sql.append(" AND l.spot_type IN ('COVERED', 'BASEMENT')");
        if (c.cctv()) sql.append(" AND 'CCTV' = ANY(l.security_features)");
        if (c.maxPricePerDayPaise() != null) {
            sql.append(" AND l.price_per_day_paise < :maxPrice");
            params.put("maxPrice", c.maxPricePerDayPaise());
        }
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        return (List<String>) query.getResultList();
    }
}
