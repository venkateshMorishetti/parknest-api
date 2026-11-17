package com.parknest.api.files;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {

    Optional<StoredFile> findByIdAndPurposeAndDeletedAtIsNull(String id, String purpose);

    List<StoredFile> findByIdInAndOwnerIdAndDeletedAtIsNull(Collection<String> ids, String ownerId);
}
