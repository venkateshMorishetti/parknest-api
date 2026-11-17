package com.parknest.api.common;

/** Distances on the map. */
public final class Geo {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private Geo() {}

    /** Straight-line distance in km (haversine). */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    /** One decimal place, like the app shows ("0.4 km"). */
    public static double round1(double km) {
        return Math.round(km * 10) / 10.0;
    }

    public static boolean inIndia(double lat, double lng) {
        return lat >= 6 && lat <= 37.5 && lng >= 68 && lng <= 97.5;
    }

    public record Point(double lat, double lng) {}

    public record Bounds(double north, double south, double east, double west) {}
}
