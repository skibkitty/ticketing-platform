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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    protected SeatEntity() {}

    public SeatEntity(EventEntity event, String section, String row, int seatNumber, SeatStatus status) {
        this.event = event;
        this.section = section;
        this.row = row;
        this.seatNumber = seatNumber;
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

    public SeatStatus getStatus() {
        return status;
    }
}