package com.parknest.api.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page copy and form options, kept as JSON in resources/content so they can change without touching code.
 * (Later these can move to the database or a CMS without changing the contract.)
 */
@RestController
public class ContentController {

    private final JsonNode home;
    private final JsonNode host;
    private final JsonNode searchOptions;

    public ContentController(ObjectMapper json) throws IOException {
        this.home = read(json, "content/home.json");
        this.host = read(json, "content/host.json");
        this.searchOptions = read(json, "content/search-options.json");
    }

    /** GET /content/home */
    @GetMapping("/content/home")
    public ResponseEntity<ApiResponse<JsonNode>> home() {
        return Responses.ok("Home content fetched", home);
    }

    /** GET /content/host */
    @GetMapping("/content/host")
    public ResponseEntity<ApiResponse<JsonNode>> host() {
        return Responses.ok("Host content fetched", host);
    }

    /** GET /meta/search-options */
    @GetMapping("/meta/search-options")
    public ResponseEntity<ApiResponse<JsonNode>> searchOptions() {
        return Responses.ok("Search options fetched", searchOptions);
    }

    static JsonNode read(ObjectMapper json, String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return json.readTree(in);
        }
    }
}
