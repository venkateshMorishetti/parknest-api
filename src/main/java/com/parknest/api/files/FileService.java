package com.parknest.api.files;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Uploads (spot photos for now): checks the type by the file's own bytes, not its name, and the size. */
@Service
public class FileService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public record Uploaded(String fileId, String fileName, String mimeType, long sizeBytes, String purpose) {}

    public record Opened(String mimeType, InputStream content) {}

    private final StoredFileRepository files;
    private final FileStorage storage;

    public FileService(StoredFileRepository files, FileStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    public Uploaded upload(String ownerId, MultipartFile file, String purpose) throws IOException {
        if (!"LISTING_PHOTO".equals(purpose)) throw ApiException.invalid("INVALID_PURPOSE", "purpose", "Unknown upload purpose");
        if (file == null || file.isEmpty()) throw ApiException.invalid("FILE_REQUIRED", "file", "Choose a file to upload");
        if (file.getSize() > MAX_BYTES) throw ApiException.invalid("FILE_TOO_LARGE", "file", "The file must be 5 MB or smaller");
        byte[] bytes = file.getBytes();
        String mime = detectImage(bytes);
        if (mime == null) throw ApiException.invalid("UNSUPPORTED_TYPE", "file", "Use a JPG, PNG or WebP photo");

        String id = Ids.newId("file");
        LocalDate today = LocalDate.now();
        String key = "listing-photos/" + today.getYear() + "/" + today.getMonthValue() + "/" + id + extension(mime);
        storage.save(key, bytes, mime);
        String name = file.getOriginalFilename() == null ? null : file.getOriginalFilename().replaceAll("[\\r\\n]", "");
        StoredFile stored = new StoredFile();
        stored.setId(id);
        stored.setOwnerId(ownerId);
        stored.setPurpose(purpose);
        stored.setStorageKey(key);
        stored.setMimeType(mime);
        stored.setSizeBytes(bytes.length);
        stored.setOriginalName(name);
        files.save(stored);
        return new Uploaded(id, name, mime, bytes.length, purpose);
    }

    /** Spot photos are public: anyone with the (unguessable) id can see them. */
    public Opened open(String fileId) throws IOException {
        StoredFile row = files.findByIdAndPurposeAndDeletedAtIsNull(fileId, "LISTING_PHOTO").orElseThrow(() -> ApiException.notFound("File"));
        Optional<InputStream> in = storage.open(row.getStorageKey());
        if (in.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "File not found", "NOT_FOUND", null, "This file is missing");
        return new Opened(row.getMimeType(), in.get());
    }

    /** JPEG, PNG or WebP by magic bytes; null for anything else. */
    static String detectImage(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
