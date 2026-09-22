package com.raydans.reservationservice.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.ResourceNotFoundException;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import com.raydans.reservationservice.web.ReservationRequest;
import com.raydans.reservationservice.web.ReservationResponse;
import com.raydans.reservationservice.web.SeatResponse;
import com.raydans.reservationservice.web.SeatStatus;
import com.raydans.reservationservice.web.SeatUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaReservationService implements ReservationService {

    static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    private static final String RESERVATION_CREATED = "reservation.ReservationCreated";

    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    JpaReservationService(
            ReservationRepository reservations,
            SeatRepository seats,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper) {
        this.reservations = reservations;
        this.seats = seats;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ReservationResponse create(ReservationRequest request, long customerId) {
        Instant holdExpiresAt = Instant.now().plus(HOLD_DURATION);
        try {
            List<SeatEntity> heldSeats = loadAndFlipSeats(request, holdExpiresAt);
            ReservationEntity reservation = saveReservation(customerId, heldSeats, holdExpiresAt);
            saveOutboxEvent(reservation, heldSeats);
            return toResponse(reservation);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new SeatUnavailableException("One or more seats are no longer available");
        }
    }

    @Override
    @Transactional
    public ReservationResponse get(long reservationId) {
        ReservationEntity reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation " + reservationId + " was not found"));
        expireIfOverdue(reservation);
        return toResponse(reservation);
    }

    @Override
    @Transactional
    public List<ReservationResponse> listByCustomerId(long customerId) {
        return reservations.findByCustomerIdOrderByIdDesc(customerId).stream()
                .peek(this::expireIfOverdue)
                .map(this::toResponse)
                .toList();
    }

    private void expireIfOverdue(ReservationEntity reservation) {
        if (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT
                && reservation.getExpiresAt().isBefore(Instant.now())) {
            reservation.markExpired();
            reservations.save(reservation);
        }
    }

    private List<SeatEntity> loadAndFlipSeats(ReservationRequest request, Instant holdExpiresAt) {
        if (request.seatIds().isEmpty()) {
            throw new SeatUnavailableException("At least one seat must be requested");
        }
        List<SeatEntity> seatList = seats.findAllById(request.seatIds());
        if (seatList.size() != new LinkedHashSet<>(request.seatIds()).size()) {
            throw new SeatUnavailableException("One or more requested seats are not available");
        }

        for (SeatEntity seat : seatList) {
            if (seat.getStatus() != SeatStatus.AVAILABLE || !seat.getEvent().getId().equals(request.eventId())) {
                throw new SeatUnavailableException("One or more seats are no longer available");
            }
        }

        for (SeatEntity seat : seatList) {
            seat.flipToHeld(holdExpiresAt);
        }

        return seatList;
    }

    private ReservationEntity saveReservation(long customerId, List<SeatEntity> heldSeats, Instant holdExpiresAt) {
        EventEntity event = heldSeats.get(0).getEvent();
        ReservationEntity reservation = new ReservationEntity(customerId, event, ReservationStatus.PENDING_PAYMENT, holdExpiresAt);
        reservation.getSeats().addAll(heldSeats);
        return reservations.saveAndFlush(reservation);
    }

    private void saveOutboxEvent(ReservationEntity reservation, List<SeatEntity> heldSeats) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new ReservationCreatedPayload(
                            reservation.getId(),
                            reservation.getCustomerId(),
                            reservation.getEvent().getId(),
                            heldSeats.stream().map(SeatEntity::getId).toList(),
                            heldSeats.stream().mapToInt(SeatEntity::getPriceCents).sum()));
            OutboxEventEntity event = new OutboxEventEntity(
                    UUID.randomUUID(),
                    "Reservation",
                    reservation.getId(),
                    RESERVATION_CREATED,
                    payload,
                    currentCorrelationId());
            outbox.save(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize ReservationCreated payload", ex);
        }
    }

    private String currentCorrelationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        return cid == null ? UUID.randomUUID().toString() : cid;
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        List<SeatResponse> seatDtos = reservation.getSeats().stream()
                .map(SeatResponse::from)
                .toList();
        int total = seatDtos.stream().mapToInt(SeatResponse::priceCents).sum();
        return new ReservationResponse(
                reservation.getId(),
                reservation.getCustomerId(),
                reservation.getEvent().getId(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt(),
                total,
                seatDtos);
    }

    public record ReservationCreatedPayload(
            long reservationId,
            long customerId,
            long eventId,
            List<Long> seatIds,
            int amountCents) {}
}