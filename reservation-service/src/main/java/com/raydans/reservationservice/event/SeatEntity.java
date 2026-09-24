package com.raydans.reservationservice.event;

import com.raydans.reservationservice.web.SeatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(schema = "reservation", name = "seats")
public class SeatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private EventEntity event;

    @Column(nullable = false, length = 50)
    private String section;

    @Column(name = "`row`", nullable = false, length = 50)
    private String row;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    protected SeatEntity() {}

    public SeatEntity(EventEntity event, String section, String row, int seatNumber, int priceCents, SeatStatus status) {
        this.event = event;
        this.section = section;
        this.row = row;
        this.seatNumber = seatNumber;
        this.priceCents = priceCents;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public EventEntity getEvent() {
        return event;
    }

    public String getSection() {
        return section;
    }

    public String getRow() {
        return row;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void flipToHeld(Instant expiresAt) {
        status = SeatStatus.HELD;
        holdExpiresAt = expiresAt;
    }

    /**
     * Releases the hold on a {@code HELD} seat back to {@code AVAILABLE} and clears its hold
     * expiry. Returns whether the seat was actually released: a seat in any other state (e.g.
     * {@code SOLD}) is left untouched — a compensating action must never hand a sold seat back
     * to the general pool.
     */
    public boolean releaseHold() {
        if (status == SeatStatus.HELD) {
            status = SeatStatus.AVAILABLE;
            holdExpiresAt = null;
            return true;
        }
        return false;
    }

    public void markSold() {
        status = SeatStatus.SOLD;
        holdExpiresAt = null;
    }

    public boolean releaseHoldIfLapsed(Instant now) {
        return status == SeatStatus.HELD
                && holdExpiresAt != null
                && holdExpiresAt.isBefore(now)
                && releaseHold();
    }
}