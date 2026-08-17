package com.uphill.appointments.boundary.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAppointmentRequest(

        @NotBlank(message = "patientId is required")
        String patientId,

        @NotBlank(message = "specialtyCode is required")
        String specialtyCode,

        @NotNull(message = "startsAt is required")
        @Future(message = "startsAt must be in the future")
        Instant startsAt) {
}
