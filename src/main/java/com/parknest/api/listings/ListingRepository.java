package com.parknest.api.listings;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, String>, ListingSearch {

    /** Locks the spot so two bookings for its last bay cannot both succeed. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findForUpdate(@Param("id") String id);

    @Query("select l from Listing l join fetch l.host where l.id = :id and l.deletedAt is null")
    Optional<Listing> findWithHost(@Param("id") String id);

    @Query("select distinct l from Listing l join fetch l.host where l.id in :ids")
    List<Listing> findWithHostByIdIn(@Param("ids") Collection<String> ids);

    List<Listing> findByHostIdAndDeletedAtIsNullOrderByCreatedAtDesc(String hostId);

    Optional<Listing> findByIdAndHostIdAndDeletedAtIsNull(String id, String hostId);

    /** File ids among these that already belong to a different spot. */
    @Query("select p.fileId from Listing l join l.photos p where p.fileId in :fileIds and l.id <> :listingId")
    List<String> fileIdsUsedElsewhere(@Param("fileIds") Collection<String> fileIds, @Param("listingId") String listingId);

    /** Bookings that start this month (not cancelled), per spot of this host: [listingId, count]. */
    @Query(nativeQuery = true, value = """
            SELECT listing_id, count(*) FROM bookings
            WHERE host_id = :hostId AND start_at >= :from AND status IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED')
            GROUP BY listing_id
            """)
    List<Object[]> bookingsSince(@Param("hostId") String hostId, @Param("from") OffsetDateTime from);
}
