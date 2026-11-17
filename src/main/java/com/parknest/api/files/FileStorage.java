package com.parknest.api.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** Where uploaded files live. Local disk for now; an S3-compatible bucket for the testing and production servers. */
public interface FileStorage {

    void save(String key, byte[] content, String mimeType) throws IOException;

    Optional<InputStream> open(String key) throws IOException;
}
