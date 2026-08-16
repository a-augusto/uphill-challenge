package com.uphill.appointments.notification;

import com.uphill.appointments.domain.Appointment;

/**
 * Notifies the patient once an appointment is confirmed. Backed by
 * {@link EmailNotificationService} in this build; the abstraction leaves room
 * for other channels (SMS, push) without touching the booking flow.
 */
public interface NotificationService {

    void sendAppointmentConfirmation(Appointment appointment);
}
