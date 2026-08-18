package com.uphill.appointments.control;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.uphill.appointments.control.events.AppointmentCancelledEvent;
import com.uphill.appointments.control.exceptions.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.exceptions.AppointmentNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.enums.AppointmentStatus;
import com.uphill.appointments.entity.repository.AppointmentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cancels an existing appointment. Unlike {@link BookingService}, this isn't
 * a candidate-pair retry loop — a single straightforward transaction is
 * enough, so no {@code BookingAttemptExecutor}-style split is needed for
 * {@code @TransactionalEventListener(AFTER_COMMIT)} to bind to it correctly.
 *
 * <p>Two entry points, deliberately: {@link #cancel(UUID)} is for the
 * authenticated admin UI, which may cancel any appointment. {@link
 * #cancel(UUID, String)} is for the public API, which has no login at all —
 * without it, anyone who learned or guessed an appointment UUID could cancel
 * any patient's booking (see DECISIONS.md, IDOR finding). It requires the
 * caller to also know the {@code patientId} the appointment was booked
 * under, and a mismatch reports as {@link AppointmentNotFoundException},
 * not a distinct "forbidden" response — telling an attacker "this UUID
 * exists but isn't yours" is itself information the not-found response
 * denies them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private final AppointmentRepository appointmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Appointment cancel(UUID appointmentId) {
        return doCancel(findForCancellation(appointmentId));
    }

    @Transactional
    public Appointment cancel(UUID appointmentId, String patientId) {
        Appointment appointment = findForCancellation(appointmentId);
        if (!appointment.getPatient().getPatientId().equals(patientId)) {
            throw new AppointmentNotFoundException("Unknown appointment: " + appointmentId);
        }
        return doCancel(appointment);
    }

    private Appointment findForCancellation(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Unknown appointment: " + appointmentId));
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentAlreadyCancelledException("Appointment already cancelled: " + appointmentId);
        }
        return appointment;
    }

    private Appointment doCancel(Appointment appointment) {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledAt(OffsetDateTime.now());
        Appointment saved = appointmentRepository.save(appointment);

        eventPublisher.publishEvent(new AppointmentCancelledEvent(saved));
        log.info("Cancelled appointment {}", saved.getId());
        return saved;
    }
}
