package com.uphill.appointments.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Port to the external room-booking system. Backed by a RestClient adapter
 * calling a WireMock stub for local development and tests.
 */
public interface RoomReservationClient {

    void reserveRoom(Long roomId, Instant startsAt, Instant endsAt, UUID appointmentId);
}
