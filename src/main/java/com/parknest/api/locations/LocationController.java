package com.parknest.api.locations;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Geo;
import com.parknest.api.common.Responses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocationController {

    private final PlaceService places;

    public LocationController(PlaceService places) {
        this.places = places;
    }

    /** GET /locations/suggest?q=hsr */
    @GetMapping("/locations/suggest")
    public ResponseEntity<ApiResponse<List<PlaceService.Place>>> suggest(@RequestParam(defaultValue = "") String q) {
        List<PlaceService.Place> found = places.suggest(q, 8);
        return Responses.ok(found.size() + (found.size() == 1 ? " place" : " places"), found);
    }

    /** GET /locations/reverse?lat=12.91&lng=77.64: the area name for "Use my current location". */
    @GetMapping("/locations/reverse")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reverse(@RequestParam double lat, @RequestParam double lng) {
        if (!Geo.inIndia(lat, lng)) throw ApiException.invalid("lat", "This location is outside India");
        PlaceService.Nearest n = places.reverse(lat, lng).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No area found", "LOCATION_NOT_FOUND", null, "We could not name the area at this location"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("placeId", n.place().placeId());
        data.put("label", n.place().label());
        data.put("area", n.place().area());
        data.put("city", n.place().city());
        data.put("geo", new Geo.Point(lat, lng));
        data.put("distanceKm", Geo.round1(n.distanceKm()));
        return Responses.ok("Area found", data);
    }
}
