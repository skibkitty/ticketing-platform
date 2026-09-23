package com.raydans.paymentservice.payment;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    /**
     * Atomically creates the payment for a reservation inside the caller's transaction,
     * but only if the reservation does not already have one. {@code status} and
     * {@code settledAt} are written directly so the row is born already settled — a
     * second ReservationCreated for the same reservation (even with a different eventId)
     * is a deterministic no-op rather than a uniqueness violation (ADR 004). Returns 1
     * when this transaction created the payment, 0 when the reservation was already paid.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO payment.payments (reservation_id, amount_cents, status, settled_at) "
                            + "VALUES (:reservationId, :amountCents, :status, :settledAt) "
                            + "ON CONFLICT (reservation_id) DO NOTHING",
            nativeQuery = true)
    int tryClaim(
            @Param("reservationId") long reservationId,
            @Param("amountCents") int amountCents,
            @Param("status") String status,
            @Param("settledAt") Instant settledAt);

    Optional<PaymentEntity> findByReservationId(@Param("reservationId") long reservationId);
}
