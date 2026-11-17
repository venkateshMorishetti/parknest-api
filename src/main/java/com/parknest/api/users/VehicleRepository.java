package com.parknest.api.users;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {

    /** Saved vehicles, the default first, then oldest first. */
    List<Vehicle> findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(String userId);

    Optional<Vehicle> findByIdAndUserIdAndDeletedAtIsNull(String id, String userId);

    /** Run before marking another vehicle as default (only one default is allowed). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Vehicle v set v.isDefault = false where v.userId = :userId and v.isDefault = true")
    int clearDefault(@Param("userId") String userId);
}
