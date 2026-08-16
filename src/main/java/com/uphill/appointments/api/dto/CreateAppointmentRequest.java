package com.uphill.appointments.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAppointmentRequest(

        @NotBlank(message = "patientName is required")
        String patientName,

        @NotBlank(message = "patientEmail is required")
        @Email(message = "patientEmail must be a valid email address")
        String patientEmail,

        String patientPhone,

        @NotBlank(message = "specialtyCode is required")
        String specialtyCode,

        @NotNull(message = "startsAt is required")
        @Future(message = "startsAt must be in the future")
        Instant startsAt) {
}
