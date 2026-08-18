package com.uphill.appointments.boundary.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAppointmentRequest(

        @NotBlank(message = "patientId is required")
        String patientId,

        @NotBlank(message = "specialtyCode is required")
        String specialtyCode,

        @NotNull(message = "date is required")
        LocalDate date,

        // Optional — omit to let the system pick a time within extended
        // business hours (9am-6pm) on the given date.
        LocalTime startTime,

        @NotNull(message = "offset is required")
        ZoneOffset offset,

        @Min(value = 15, message = "durationMinutes must be at least 15")
        @Max(value = 480, message = "durationMinutes must be at most 480")
        Integer durationMinutes) {
}
