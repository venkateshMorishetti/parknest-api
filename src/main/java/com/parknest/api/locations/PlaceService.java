package com.parknest.api.locations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parknest.api.common.Geo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Named areas (Bengaluru for now) used for typing suggestions, naming the area at a point, and turning a typed area
 * name into a map point for search. Replace with a geocoder (Photon / Ola Maps) when covering more cities.
 */
@Service
public class PlaceService {

    public record Place(String placeId, String label, String area, String city, Geo.Point geo) {}

    /** How far a point can be from an area's centre and still be named after it. */
    static final double REVERSE_MAX_KM = 3.0;

    private final List<Place> places;

    public PlaceService(ObjectMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("content/places.json").getInputStream()) {
            this.places = json.readValue(in, new TypeReference<List<Place>>() {});
        }
    }

    /** Areas whose name starts with, or contains, the typed text (starts-with first). */
    public List<Place> suggest(String query, int limit) {
        String q = normalise(query);
        if (q.length() < 2) return List.of();
        return places.stream()
                .filter(p -> normalise(p.label()).contains(q))
                .sorted(Comparator.comparing((Place p) -> !normalise(p.area()).startsWith(q)).thenComparing(Place::label))
                .limit(limit)
                .toList();
    }

    /** The nearest named area within a few km. */
    public Optional<Nearest> reverse(double lat, double lng) {
        return places.stream()
                .map(p -> new Nearest(p, Geo.distanceKm(lat, lng, p.geo().lat(), p.geo().lng())))
                .filter(n -> n.distanceKm() <= REVERSE_MAX_KM)
                .min(Comparator.comparingDouble(Nearest::distanceKm));
    }

    /** "HSR Layout" or "HSR Layout, Bengaluru" -> the place. */
    public Optional<Place> byLabel(String label) {
        String q = normalise(label);
        if (q.isEmpty()) return Optional.empty();
        return places.stream()
                .filter(p -> normalise(p.label()).equals(q) || normalise(p.area()).equals(q) || q.startsWith(normalise(p.area()) + ","))
                .findFirst();
    }

    public record Nearest(Place place, double distanceKm) {}

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
