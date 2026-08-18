package com.uphill.appointments.control;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.AppointmentStatus;
import com.uphill.appointments.entity.repository.AppointmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * Cancels an existing appointment. Unlike {@link BookingService}, this isn't
 * a candidate-pair retry loop — a single straightforward transaction is
 * enough, so no {@code BookingAttemptExecutor}-style split is needed for
 * {@code @TransactionalEventListener(AFTER_COMMIT)} to bind to it correctly.
 */
@Service
@RequiredArgsConstructor
public class CancellationService {

    private final AppointmentRepository appointmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Appointment cancel(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Unknown appointment: " + appointmentId));
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentAlreadyCancelledException("Appointment already cancelled: " + appointmentId);
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledAt(OffsetDateTime.now());
        Appointment saved = appointmentRepository.save(appointment);

        eventPublisher.publishEvent(new AppointmentCancelledEvent(saved));
        return saved;
    }
}
