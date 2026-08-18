package com.uphill.appointments.boundary.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAppointmentRequest(

        @NotBlank(message = "patientId is required")
        @Schema(example = "PAT-000001", description = "Business id from the seeded patient list, not the DB row id")
        String patientId,

        @NotBlank(message = "specialtyCode is required")
        @Schema(example = "CARDIOLOGY",
                description = "One of the seeded codes: CARDIOLOGY, DERMATOLOGY, GENERAL_PRACTICE, PEDIATRICS")
        String specialtyCode,

        @NotNull(message = "date is required")
        @Schema(example = "2026-08-26")
        LocalDate date,

        // Optional — omit to let the system pick a time within extended
        // business hours (9am-6pm) on the given date.
        @Schema(example = "09:30:00",
                description = "Omit to let the system pick a free time within extended business hours "
                        + "(9am-6pm) on the given date instead of an exact instant")
        LocalTime startTime,

        @NotNull(message = "offset is required")
        @Schema(example = "+00:00")
        ZoneOffset offset,

        @Min(value = 15, message = "durationMinutes must be at least 15")
        @Max(value = 480, message = "durationMinutes must be at most 480")
        @Schema(example = "30", description = "Minutes, 15-480, must be a multiple of 15. Defaults to 30 if omitted")
        Integer durationMinutes) {
}
