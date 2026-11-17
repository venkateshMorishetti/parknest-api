package com.parknest.api.listings;

import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import com.parknest.api.listings.ListingDtos.ListingDetail;
import com.parknest.api.listings.ListingDtos.ListingSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings")
public class ListingController {

    private final ListingService listings;

    public ListingController(ListingService listings) {
        this.listings = listings;
    }

    /** POST /listings/search: spots free for the whole stay, with distances, sorting and pages. */
    @PostMapping("/search")
    public ResponseEntity<ApiResponse<List<ListingSummary>>> search(@Valid @RequestBody ListingDtos.SearchRequest req) {
        ListingService.SearchPage page = listings.search(req);
        Object total = ((java.util.Map<?, ?>) page.meta().get("pagination")).get("totalItems");
        String message = Integer.valueOf(1).equals(total) ? "1 spot found" : total + " spots found";
        return Responses.ok(message, page.items(), page.meta());
    }

    /** GET /listings/{id}?lat=&lng= */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ListingDetail>> detail(@PathVariable String id, @RequestParam(required = false) Double lat,
                                                              @RequestParam(required = false) Double lng) {
        return Responses.ok("Listing fetched", listings.detail(id, lat, lng));
    }

    /** POST /listings/{id}/quote */
    @PostMapping("/{id}/quote")
    public ResponseEntity<ApiResponse<ListingDtos.Quote>> quote(@PathVariable String id, @Valid @RequestBody ListingDtos.QuoteRequest req) {
        return Responses.ok("Quote calculated", listings.quote(id, req));
    }
}
