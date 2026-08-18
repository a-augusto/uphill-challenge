package com.uphill.appointments.boundary.external;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Port to the external doctor-calendar system. In production this would be a
 * real scheduling platform the hospital's doctors use; here it is backed by a
 * RestClient adapter calling a WireMock stub.
 */
public interface DoctorCalendarClient {

    void reserveSlot(Long doctorId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId);

    void releaseSlot(Long doctorId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId);
}
