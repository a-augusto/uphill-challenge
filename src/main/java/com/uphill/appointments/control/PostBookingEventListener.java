package com.uphill.appointments.control;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.boundary.external.DoctorCalendarClient;
import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.boundary.notification.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fans out the post-booking side effects (calendar update, room reservation,
 * confirmation email) once the booking transaction has actually committed —
 * so we never tell an external system about an appointment that could still
 * roll back. Each call is independently best-effort: a failure here is
 * logged, not surfaced to the patient, since the booking itself already
 * succeeded and the HTTP response has been sent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostBookingEventListener {

    private final DoctorCalendarClient doctorCalendarClient;
    private final RoomReservationClient roomReservationClient;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentBooked(AppointmentBookedEvent event) {
        Appointment appointment = event.appointment();

        runBestEffort("doctor calendar update", appointment, () ->
                doctorCalendarClient.reserveSlot(
                        appointment.getDoctor().getId(),
                        appointment.getStartsAt(),
                        appointment.getEndsAt(),
                        appointment.getId()));

        runBestEffort("room reservation", appointment, () ->
                roomReservationClient.reserveRoom(
                        appointment.getRoom().getId(),
                        appointment.getStartsAt(),
                        appointment.getEndsAt(),
                        appointment.getId()));

        runBestEffort("confirmation email", appointment, () ->
                notificationService.sendAppointmentConfirmation(appointment));
    }

    private void runBestEffort(String actionName, Appointment appointment, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Post-booking action '{}' failed for appointment {}", actionName, appointment.getId(), e);
        }
    }
}
