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

    public static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody ReservationRequest request,
            @RequestHeader(value = CUSTOMER_HEADER, required = false, defaultValue = "0") long customerId,
            UriComponentsBuilder builder) {
        ReservationResponse created = reservations.create(request, customerId);
        URI location = builder.path("/api/v1/reservations/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{reservationId}")
    ReservationResponse get(@PathVariable("reservationId") long reservationId) {
        return reservations.get(reservationId);
    }

    @GetMapping
    List<ReservationResponse> listByCustomer(@RequestParam("customerId") long customerId) {
        return reservations.listByCustomerId(customerId);
    }
}