package com.uphill.appointments.booking;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uphill.appointments.domain.Appointment;
import com.uphill.appointments.domain.Doctor;
import com.uphill.appointments.domain.PatientInfo;
import com.uphill.appointments.domain.Room;
import com.uphill.appointments.domain.Specialty;
import com.uphill.appointments.events.AppointmentBookedEvent;
import com.uphill.appointments.repository.AppointmentRepository;

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
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Appointment attemptBook(
            Doctor doctor, Room room, Specialty specialty, PatientInfo patient, Instant startsAt, Instant endsAt) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(endsAt);
        // saveAndFlush forces the INSERT (and the unique-constraint check) to run
        // now, inside this attempt's own transaction, so a violation surfaces here
        // and can be retried rather than deferred to commit time.
        Appointment saved = appointmentRepository.saveAndFlush(appointment);
        eventPublisher.publishEvent(new AppointmentBookedEvent(saved));
        return saved;
    }
}
