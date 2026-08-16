package com.uphill.appointments.events;

import com.uphill.appointments.domain.Appointment;

public record AppointmentBookedEvent(Appointment appointment) {
}
