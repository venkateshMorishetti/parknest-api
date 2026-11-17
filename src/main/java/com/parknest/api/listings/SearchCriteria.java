package com.parknest.api.listings;

import com.parknest.api.common.Geo;
import java.time.OffsetDateTime;

/** What ListingSearch filters on. Null fields are not filtered. */
public record SearchCriteria(
        Geo.Bounds box,
        OffsetDateTime start,
        OffsetDateTime end,
        String vehicleType,
        String spotType,
        boolean instantOnly,
        boolean coveredOnly,
        boolean cctv,
        Long maxPricePerDayPaise) {}
