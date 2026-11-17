package com.parknest.api.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps files in a folder (parknest.storage.local-dir). Fine for one server; use object storage when there are more. */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${parknest.storage.local-dir:./data/uploads}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public void save(String key, byte[] content, String mimeType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    @Override
    public Optional<InputStream> open(String key) throws IOException {
        Path file = resolve(key);
        return Files.isRegularFile(file) ? Optional.of(Files.newInputStream(file)) : Optional.empty();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Bad storage key");
        return path;
    }
}
