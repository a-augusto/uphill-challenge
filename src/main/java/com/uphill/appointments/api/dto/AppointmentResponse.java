package com.uphill.appointments.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.uphill.appointments.domain.Appointment;

public record AppointmentResponse(
        UUID id,
        String patientName,
        String specialty,
        String doctorName,
        String roomName,
        Instant startsAt,
        Instant endsAt,
        String status) {

    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getPatient().getName(),
                appointment.getSpecialty().getCode(),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName(),
                appointment.getStartsAt(),
                appointment.getEndsAt(),
                appointment.getStatus().name());
    }
}
