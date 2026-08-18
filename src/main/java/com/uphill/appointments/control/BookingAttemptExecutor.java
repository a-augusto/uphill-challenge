package com.uphill.appointments.control;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * Persists a single candidate (doctor, room) booking attempt in its own,
 * genuinely independent transaction. Split out of BookingService because
 * {@code @Transactional(REQUIRES_NEW)} only takes effect through a Spring
 * proxy — called from within BookingService itself, the annotation would be
 * silently ignored (self-invocation bypasses the proxy), leaving every retry
 * after the first constraint violation running inside the same
 * already-aborted transaction instead of a fresh one.
 *
 * <p>The room-reservation call is synchronous and inside this same
 * transaction: a room must actually be secured for the appointment to be
 * valid, so the external system's answer gates the attempt exactly like the
 * DB range-exclusion constraint does — if it rejects, this transaction rolls
 * back (undoing the insert) and {@link BookingService} tries the next
 * candidate pair. The doctor-calendar update has no such correctness
 * requirement (fire-and-forget via Kafka instead) so it stays in the
 * after-commit best-effort fan-out in {@link AppointmentEventListener}.
 *
 * <p>The {@link AppointmentBookedEvent} is published from here too, not from
 * BookingService, and deliberately inside this same transaction: it gives
 * {@code @TransactionalEventListener(AFTER_COMMIT)} a transaction to bind to
 * without BookingService needing an outer @Transactional of its own. An
 * outer transaction spanning the whole retry loop would hold one connection
 * per in-flight request for the loop's entire duration while each attempt
 * here needs a second, concurrently — under load that starves the pool (N
 * concurrent requests each holding 1 of N connections, all waiting on a 2nd
 * that never frees up). Keeping every DB interaction — including the event
 * that depends on it — inside this single short-lived transaction avoids
 * that entirely.
 */
@Component
@RequiredArgsConstructor
class BookingAttemptExecutor {

    private final AppointmentRepository appointmentRepository;
    private final RoomReservationClient roomReservationClient;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Appointment attemptBook(
            Doctor doctor, Room room, Specialty specialty, Patient patient, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(endsAt);
        // saveAndFlush forces the INSERT (and the unique-constraint check) to run
        // now, inside this attempt's own transaction, so a violation surfaces here
        // and can be retried rather than deferred to commit time. Cheapest check
        // first, before the network call below.
        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        try {
            roomReservationClient.reserveRoom(room.getId(), startsAt, endsAt, saved.getId());
        } catch (Exception e) {
            throw new RoomReservationFailedException(
                    "Room " + room.getId() + " could not be reserved for slot " + startsAt, e);
        }

        eventPublisher.publishEvent(new AppointmentBookedEvent(saved));
        return saved;
    }
}
