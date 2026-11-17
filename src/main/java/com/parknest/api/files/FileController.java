package com.parknest.api.files;

import com.parknest.api.common.ApiResponse;
import com.parknest.api.common.Responses;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    /** POST /uploads (multipart: file, purpose=LISTING_PHOTO) */
    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<FileService.Uploaded>> upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
                                                                    @RequestParam("purpose") String purpose) throws IOException {
        return Responses.created("File uploaded", files.upload(jwt.getSubject(), file, purpose));
    }

    /** GET /files/{id}: the photo itself (public, cached for a day). */
    @GetMapping("/files/{id}")
    public ResponseEntity<InputStreamResource> file(@PathVariable String id) throws IOException {
        FileService.Opened f = files.open(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.mimeType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(f.content()));
    }
}
