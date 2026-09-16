package com.raydans.reservationservice.web;

/** Raised when the same (section, row, seatNumber) is supplied twice for one event. */
public class DuplicateSeatException extends RuntimeException {

    public DuplicateSeatException(String message) {
        super(message);
    }
}
