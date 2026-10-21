package com.raydans.reservationservice.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    /**
     * The one read that returns a Reservation to a caller, scoped to the Customer
     * it belongs to.
     *
     * <p>The Customer is part of the query rather than a check applied to the
     * result, which is the whole point: another Customer's row is never loaded,
     * so it cannot be returned by mistake from a code path that forgot the check,
     * and the read path's expiry side effect cannot fire on it either. An id that
     * does not exist and one belonging to someone else are both absent here, so
     * this cannot be used to probe which reservation ids are taken (ADR 014).
     */
    Optional<ReservationEntity> findByIdAndCustomerId(long id, long customerId);

    List<ReservationEntity> findByCustomerIdOrderByIdDesc(long customerId);

    List<ReservationEntity> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant now);
}