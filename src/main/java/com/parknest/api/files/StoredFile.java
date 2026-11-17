package com.parknest.api.files;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An uploaded file (table files). The bytes live in FileStorage under storageKey. */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
public class StoredFile {

    @Id
    private String id;

    private String ownerId;
    /** LISTING_PHOTO */
    private String purpose;
    private String storageKey;
    private String mimeType;
    private int sizeBytes;
    private String originalName;
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
