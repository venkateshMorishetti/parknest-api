package com.parknest.api.bookings;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByIdAndDriverId(String id, String driverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findForUpdate(@Param("id") String id);

    List<Booking> findByDriverIdOrderByStartAtAsc(String driverId);

    List<Booking> findByHostIdAndStatusInOrderByStartAtAsc(String hostId, Collection<String> statuses);

    List<Booking> findByHostIdAndStartAtBetween(String hostId, OffsetDateTime from, OffsetDateTime to);

    /** Requests waiting for this host, oldest first. */
    List<Booking> findByHostIdAndStatusOrderByCreatedAtAsc(String hostId, String status);

    /** Other waiting requests for the same spot whose times overlap. */
    @Query("""
            select b from Booking b where b.listingId = :listingId and b.status = 'PENDING_HOST_APPROVAL' and b.id <> :id
              and b.startAt < :endAt and b.endAt > :startAt
            """)
    List<Booking> overlappingRequests(@Param("listingId") String listingId, @Param("id") String id,
                                      @Param("startAt") OffsetDateTime startAt, @Param("endAt") OffsetDateTime endAt);

    /** The most bays in use at once in [start, end) by bookings that hold a bay (see listing_peak_occupancy in V1). */
    @Query(nativeQuery = true, value = "SELECT listing_peak_occupancy(:listingId, :start, :end, CAST(:ignoreId AS text))")
    int peakOccupancy(@Param("listingId") String listingId, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
                      @Param("ignoreId") String ignoreId);

    @Query(nativeQuery = true, value = "SELECT nextval('booking_ref_seq')")
    long nextRef();

    /** Unpaid holds whose time ran out. */
    @Modifying(clearAutomatically = true)
    @Query("update Booking b set b.status = 'EXPIRED', b.updatedAt = :now where b.status = 'PENDING_PAYMENT' and b.holdExpiresAt < :now")
    int expireHolds(@Param("now") OffsetDateTime now);

    /** Completed bookings not yet in a payout, oldest first. */
    @Query("""
            select b from Booking b where b.status in ('COMPLETED', 'CANCELLED') and b.hostEarningsPaise > 0
              and not exists (select 1 from PayoutItem i where i.bookingId = b.id)
            order by b.checkedOutAt
            """)
    List<Booking> completedNotPaidOut();

    @Query("""
            select b from Booking b where b.hostId = :hostId and b.status in ('COMPLETED', 'CANCELLED') and b.hostEarningsPaise > 0
              and not exists (select 1 from PayoutItem i where i.bookingId = b.id)
            """)
    List<Booking> completedNotPaidOutForHost(@Param("hostId") String hostId);
}
