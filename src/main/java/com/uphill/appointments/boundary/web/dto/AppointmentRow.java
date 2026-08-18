package com.uphill.appointments.boundary.web.dto;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;

/**
 * Wraps {@link AppointmentResponse} with pre-formatted display strings for
 * the admin table (slot times + duration), same approach
 * {@code EmailSlotFormatter} uses for the confirmation emails - keeps
 * zone/pattern logic server-side instead of leaning on Thymeleaf's
 * temporal-formatting expression utilities. Takes the display zone as a
 * parameter rather than hardcoding one, so the "Europe/Lisbon" choice stays
 * a decision the caller (the controller) owns.
 */
public record AppointmentRow(AppointmentResponse appointment, String startsAtDisplay, String endsAtDisplay,
        String durationDisplay) {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public static AppointmentRow from(AppointmentResponse appointment, ZoneId zone) {
        return new AppointmentRow(appointment,
                DISPLAY_FORMAT.format(appointment.startsAt().atZoneSameInstant(zone)),
                DISPLAY_FORMAT.format(appointment.endsAt().atZoneSameInstant(zone)),
                formatDuration(Duration.between(appointment.startsAt(), appointment.endsAt())));
    }

    private static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours == 0) {
            return minutes + " min";
        }
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "min";
    }
}
