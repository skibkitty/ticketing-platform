package com.raydans.reservationservice.web;

import com.raydans.reservationservice.reservation.ReservationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    /**
     * The header the gateway writes the verified caller's {@code Customer.id}
     * into (ADR 002).
     *
     * <p>Required on all three operations, and the only thing a read is scoped by.
     * A Reservation belongs to a Customer, so "whose reservation is this" is not
     * a parameter a caller supplies — it is the answer to "who is asking",
     * which the gateway has already established (ADR 014).
     */
    public static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody ReservationRequest request,
            @RequestHeader(value = CUSTOMER_HEADER, required = true) long customerId,
            UriComponentsBuilder builder) {
        ReservationResponse created = reservations.create(request, customerId);
        URI location = builder.path("/api/v1/reservations/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{reservationId}")
    ReservationResponse get(
            @PathVariable("reservationId") long reservationId,
            @RequestHeader(value = CUSTOMER_HEADER, required = true) long customerId) {
        // The Customer travels with the lookup rather than being compared against
        // the result, so this route has no way to answer for anyone but its
        // caller. The header is required, which also makes this route
        // unreachable by anything that did not come through the gateway (ADR 014).
        return reservations.get(reservationId, customerId);
    }

    /**
     * The calling Customer's reservations, and nobody else's.
     *
     * <p>Scoped by {@link #CUSTOMER_HEADER} — the id the gateway derived from the
     * token subject it verified (ADR 002) — and by nothing else. The
     * {@code customerId} parameter is accepted and refused with 400 when it
     * disagrees with the header rather than honoured, on the reasoning ADR 011
     * records for the same situation: it is redundant rather than dangerous, and
     * refusing only the mismatch means a client that has started believing it can
     * choose whose reservations it reads fails its own tests instead of quietly
     * receiving its own rows.
     */
    @GetMapping
    List<ReservationResponse> listMine(
            @RequestHeader(value = CUSTOMER_HEADER, required = true) long customerId,
            @RequestParam(name = "customerId", required = false) Long claimedCustomerId) {
        if (claimedCustomerId != null && claimedCustomerId != customerId) {
            // 400 rather than 403: the caller is entitled to their own reservations
            // and this is not it, so the request contradicts itself rather than
            // exceeding a permission. The message names both ids, because "you may
            // not read that" would describe a rule that does not exist here.
            throw new IllegalArgumentException(
                    "customerId " + claimedCustomerId + " does not match the authenticated caller ("
                            + customerId + "). This route reads only the caller's own reservations, and a"
                            + " Reservation belonging to another Customer is not readable here.");
        }
        return reservations.listByCustomerId(customerId);
    }
}