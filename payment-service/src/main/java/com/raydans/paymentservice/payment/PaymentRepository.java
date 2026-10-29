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
     *
     * <p>{@code customerId} is the reservation's owner, recorded so the status read can be
     * scoped by it (ADR 016). A duplicate never rewrites it: {@code DO NOTHING} keeps the
     * owner the first event recorded.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO payment.payments (reservation_id, customer_id, amount_cents, status, settled_at) "
                            + "VALUES (:reservationId, :customerId, :amountCents, :status, :settledAt) "
                            + "ON CONFLICT (reservation_id) DO NOTHING",
            nativeQuery = true)
    int tryClaim(
            @Param("reservationId") long reservationId,
            @Param("customerId") long customerId,
            @Param("amountCents") int amountCents,
            @Param("status") String status,
            @Param("settledAt") Instant settledAt);

    /**
     * Atomically moves a reservation's Payment from {@code SUCCEEDED} to {@code REFUNDED}
     * inside the caller's transaction. The status guard is in the statement, so the
     * transition happens at most once however many deliveries race for it: the first
     * returns 1, and every later one (or one for a Payment that never succeeded) returns 0.
     */
    @Modifying
    @Query(
            value =
                    "UPDATE payment.payments SET status = 'REFUNDED', refunded_at = :refundedAt "
                            + "WHERE reservation_id = :reservationId AND status = 'SUCCEEDED'",
            nativeQuery = true)
    int tryRefund(@Param("reservationId") long reservationId, @Param("refundedAt") Instant refundedAt);

    Optional<PaymentEntity> findByReservationId(@Param("reservationId") long reservationId);

    /**
     * The status read's lookup. The Customer is part of the query rather than compared
     * against its result, so a Payment that is not the caller's is never loaded, and an
     * unowned id and an unissued one are the same absence (ADR 014, ADR 016).
     */
    Optional<PaymentEntity> findByReservationIdAndCustomerId(long reservationId, long customerId);
}
