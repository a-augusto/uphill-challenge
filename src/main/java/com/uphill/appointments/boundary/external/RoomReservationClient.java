package com.uphill.appointments.boundary.external;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Port to the external room-booking system. Backed by a RestClient adapter
 * calling a WireMock stub for local development and tests.
 */
public interface RoomReservationClient {

    List<Long> findAvailableRoomIds(OffsetDateTime date);

    void reserveRoom(Long roomId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId);

    void releaseRoom(Long roomId, UUID appointmentId);
}
