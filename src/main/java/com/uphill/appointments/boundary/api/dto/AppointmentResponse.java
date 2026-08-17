package com.uphill.appointments.boundary.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.uphill.appointments.entity.Appointment;

public record AppointmentResponse(
        UUID id,
        String patientId,
        String patientName,
        String specialty,
        String doctorName,
        String roomName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String status) {

    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getPatient().getPatientId(),
                appointment.getPatient().getName(),
                appointment.getSpecialty().getCode(),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName(),
                appointment.getStartsAt(),
                appointment.getEndsAt(),
                appointment.getStatus().name());
    }
}
