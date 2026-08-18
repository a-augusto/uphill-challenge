package com.uphill.appointments.boundary.external.kafka;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload published to the doctor-calendar Kafka topic. The real doctor-
 * calendar system (outside this app) would own its own consumer for this
 * topic — we're only responsible for reliably publishing it.
 */
public record DoctorCalendarUpdateEvent(
        UUID appointmentId, Long doctorId, OffsetDateTime startsAt, OffsetDateTime endsAt,
        DoctorCalendarEventType type) {
}
