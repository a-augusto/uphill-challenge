package com.uphill.appointments.control;

/**
 * Thrown when the external room-reservation system rejects or fails to
 * confirm a room for a booking attempt. Caught by {@link BookingService}'s
 * retry loop exactly like a lost race on the DB unique constraint — the
 * candidate (doctor, room) pair didn't work, try the next one.
 */
public class RoomReservationFailedException extends RuntimeException {

    public RoomReservationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
