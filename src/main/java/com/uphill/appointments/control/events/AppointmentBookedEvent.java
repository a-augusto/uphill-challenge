package com.uphill.appointments.control.events;

import com.uphill.appointments.entity.Appointment;

public record AppointmentBookedEvent(Appointment appointment) {
}
