package com.uphill.appointments.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Port to the external doctor-calendar system. In production this would be a
 * real scheduling platform the hospital's doctors use; here it is backed by a
 * RestClient adapter calling a WireMock stub.
 */
public interface DoctorCalendarClient {

    void reserveSlot(Long doctorId, Instant startsAt, Instant endsAt, UUID appointmentId);
}
