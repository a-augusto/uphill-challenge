package com.uphill.appointments.control.events;

import com.uphill.appointments.entity.Appointment;

public record AppointmentCancelledEvent(Appointment appointment) {
}
