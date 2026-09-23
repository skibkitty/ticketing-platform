package com.raydans.paymentservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(schema = "payment", name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private long reservationId;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // DB-owned (DEFAULT now()), never set or updated from JPA: insertable/updatable false.
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected PaymentEntity() {}

    public PaymentEntity(long reservationId, int amountCents, PaymentStatus status) {
        this.reservationId = reservationId;
        this.amountCents = amountCents;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public long getReservationId() {
        return reservationId;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
