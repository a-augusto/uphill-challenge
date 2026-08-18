package com.uphill.appointments.control;

import com.uphill.appointments.entity.Appointment;

public record AppointmentCancelledEvent(Appointment appointment) {
}
