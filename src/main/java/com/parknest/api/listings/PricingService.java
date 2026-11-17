package com.parknest.api.listings;

import com.parknest.api.common.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The price of a stay (used by quotes now and bookings later). All amounts in paise.
 *  - under 24 hours: hourly rate x hours (part hours rounded up), capped at the daily rate
 *  - 24 hours or more: daily rate x days (part days rounded up)
 *  - service fee: ₹10 per charged day; GST 18% on the service fee
 * Keep in sync with the frontend mock (mock-handlers.ts, price()).
 */
@Service
public class PricingService {

    static final long SERVICE_FEE_PER_DAY_PAISE = 1000;
    static final int GST_PERCENT = 18;
    private static final Map<String, String> VEHICLE_NAMES = Map.of("HATCHBACK", "a hatchback", "SEDAN", "a sedan", "SUV", "an SUV", "BIKE", "a bike");

    public record Stay(OffsetDateTime start, OffsetDateTime end) {}

    public record Price(int chargedDays, long hours, long parkingPaise, String parkingLabel, long serviceFeePaise, long gstPaise) {

        public long totalPaise() {
            return parkingPaise + serviceFeePaise + gstPaise;
        }

        public List<ListingDtos.QuoteLine> lines() {
            return List.of(
                    new ListingDtos.QuoteLine("PARKING", parkingLabel, parkingPaise / 100),
                    new ListingDtos.QuoteLine("SERVICE_FEE", "Service fee", serviceFeePaise / 100),
                    new ListingDtos.QuoteLine("GST", "GST on fee", gstPaise / 100));
        }
    }

    /** Parses and checks the times: end after start, start not in the past (5 minutes' grace). */
    public Stay stay(String startAt, String endAt) {
        OffsetDateTime start;
        OffsetDateTime end;
        try {
            start = OffsetDateTime.parse(startAt);
            end = OffsetDateTime.parse(endAt);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.invalid("INVALID_RANGE", "startAt", "Use dates like 2026-11-20T09:00:00+05:30");
        }
        if (!end.isAfter(start)) throw ApiException.invalid("INVALID_RANGE", "endAt", "End time must be after start time");
        if (start.isBefore(OffsetDateTime.now().minusMinutes(5))) throw ApiException.invalid("START_IN_PAST", "startAt", "Start time must be in the future");
        if (Duration.between(start, end).toDays() > 90) throw ApiException.invalid("INVALID_RANGE", "endAt", "Book up to 90 days at a time");
        return new Stay(start, end);
    }

    public Price price(Listing listing, Stay stay, String vehicleType) {
        if (!listing.getVehicleTypes().contains(vehicleType)) {
            throw ApiException.invalid("VEHICLE_NOT_SUPPORTED", "vehicleType", "This spot does not fit " + VEHICLE_NAMES.getOrDefault(vehicleType, "this vehicle"));
        }
        long minutes = Duration.between(stay.start(), stay.end()).toMinutes();
        long hours = (minutes + 59) / 60;
        long perDay = listing.getPricePerDayPaise();
        long perHour = listing.getPricePerHourPaise();
        long parking;
        String label;
        int chargedDays;
        if (hours < 24) {
            parking = Math.min(hours * perHour, perDay);
            label = parking == perDay ? rupees(perDay) + " × 1 day" : rupees(perHour) + " × " + hours + (hours == 1 ? " hour" : " hours");
            chargedDays = 1;
        } else {
            chargedDays = (int) ((hours + 23) / 24);
            parking = chargedDays * perDay;
            label = rupees(perDay) + " × " + chargedDays + " days";
        }
        long serviceFee = Math.max(SERVICE_FEE_PER_DAY_PAISE, chargedDays * SERVICE_FEE_PER_DAY_PAISE);
        // GST rounded to whole rupees, as shown in the app.
        long gst = Math.round(serviceFee * GST_PERCENT / 100.0 / 100.0) * 100;
        return new Price(chargedDays, hours, parking, label, serviceFee, gst);
    }

    static String rupees(long paise) {
        return "₹" + (paise / 100);
    }
}
