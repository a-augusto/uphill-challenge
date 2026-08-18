package com.uphill.appointments.control;

/**
 * Thrown when the external room-reservation system's availability check
 * itself can't be completed (network error, non-2xx, timeout) — distinct
 * from {@link RoomReservationFailedException}, which means the external
 * system was reached and rejected one specific reservation attempt. This one
 * means we couldn't even determine the candidate list.
 */
public class RoomAvailabilityCheckFailedException extends RuntimeException {

    public RoomAvailabilityCheckFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
