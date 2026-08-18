package com.uphill.appointments.boundary.web.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Backs the admin booking form - start/end time rather than start
 * time/duration minutes, since a time range is what an admin picking a slot
 * on a calendar-like page actually thinks in (the JSON API keeps
 * start+duration, and the day-only auto-pick flow, unchanged - this is a
 * web-UI-only shape). Both are plain Strings, not {@code LocalTime}: an
 * empty HTML form field submits as {@code ""}, and Spring's default binder
 * rejects that outright for non-String types (there's no
 * {@code BindingResult} on the controller method to catch it gracefully)
 * rather than treating it as absent the way an omitted JSON key does.
 * Parsed by hand via {@link #parsedStartTime()}/{@link #parsedEndTime()}
 * instead.
 */
public record BookingForm(String patientId, String specialtyCode, LocalDate date, String startTime,
        String endTime) {

    public LocalTime parsedStartTime() {
        return (startTime == null || startTime.isBlank()) ? null : LocalTime.parse(startTime);
    }

    public LocalTime parsedEndTime() {
        return (endTime == null || endTime.isBlank()) ? null : LocalTime.parse(endTime);
    }

    public Integer durationMinutes() {
        LocalTime start = parsedStartTime();
        LocalTime end = parsedEndTime();
        if (start == null || end == null) {
            return null;
        }
        return (int) Duration.between(start, end).toMinutes();
    }
}
