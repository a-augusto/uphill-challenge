package com.uphill.appointments.boundary.notification;

import com.uphill.appointments.entity.Appointment;

/**
 * Notifies the patient once an appointment is confirmed. Backed by email in
 * this build - {@link AsciiArtEmailNotificationService} outside the
 * {@code prod} profile, {@link HtmlEmailNotificationService} within it - but
 * the abstraction leaves room for other channels (SMS, push) without
 * touching the booking flow.
 */
public interface NotificationService {

    void sendAppointmentConfirmation(Appointment appointment);

    void sendAppointmentCancellation(Appointment appointment);
}
