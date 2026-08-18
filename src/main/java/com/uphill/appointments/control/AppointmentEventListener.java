package com.uphill.appointments.control;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.boundary.external.DoctorCalendarClient;
import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.boundary.notification.NotificationService;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fans out the post-booking and post-cancellation side effects that have no
 * correctness requirement (calendar sync, email, room release) once the
 * relevant transaction has actually committed — so we never tell an external
 * system about a change that could still roll back. Each call is
 * independently best-effort: a failure here is logged, not surfaced to the
 * caller, since the state change itself already succeeded and the HTTP
 * response has been sent.
 *
 * <p>Room <em>reservation</em> is deliberately NOT here — it's load-bearing
 * (a room must actually be secured for a booking to be valid), so it's
 * synchronous and gates the booking attempt itself, in
 * {@link BookingAttemptExecutor}. Room <em>release</em> on cancellation is
 * the opposite: best-effort here, not gating — a patient's cancellation
 * should never be blocked by a downstream system's availability.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentEventListener {

    private final DoctorCalendarClient doctorCalendarClient;
    private final RoomReservationClient roomReservationClient;
    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentBooked(AppointmentBookedEvent event) {
        Appointment appointment = event.appointment();
        meterRegistry.counter("appointments.booked", "specialty", appointment.getSpecialty().getCode()).increment();

        runBestEffort("doctor calendar reserve", appointment, () ->
                doctorCalendarClient.reserveSlot(
                        appointment.getDoctor().getId(),
                        appointment.getStartsAt(),
                        appointment.getEndsAt(),
                        appointment.getId()));

        runBestEffort("confirmation email", appointment, () ->
                notificationService.sendAppointmentConfirmation(appointment));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCancelled(AppointmentCancelledEvent event) {
        Appointment appointment = event.appointment();
        meterRegistry.counter("appointments.cancelled", "specialty", appointment.getSpecialty().getCode()).increment();

        runBestEffort("room release", appointment, () ->
                roomReservationClient.releaseRoom(appointment.getRoom().getId(), appointment.getId()));

        runBestEffort("doctor calendar release", appointment, () ->
                doctorCalendarClient.releaseSlot(
                        appointment.getDoctor().getId(),
                        appointment.getStartsAt(),
                        appointment.getEndsAt(),
                        appointment.getId()));

        runBestEffort("cancellation email", appointment, () ->
                notificationService.sendAppointmentCancellation(appointment));
    }

    private void runBestEffort(String actionName, Appointment appointment, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Post-action '{}' failed for appointment {}", actionName, appointment.getId(), e);
        }
    }
}
