package com.uphill.appointments.control;

import com.uphill.appointments.entity.Appointment;

public record AppointmentBookedEvent(Appointment appointment) {
}
