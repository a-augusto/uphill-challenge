package com.uphill.appointments.boundary.external;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Port to the external room-booking system. Backed by a RestClient adapter
 * calling a WireMock stub for local development and tests.
 */
public interface RoomReservationClient {

    void reserveRoom(Long roomId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId);
}
