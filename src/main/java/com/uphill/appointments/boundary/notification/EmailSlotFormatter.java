package com.uphill.appointments.boundary.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import com.uphill.appointments.entity.Appointment;

/**
 * Formats an appointment's slot for display in an email — shared by both
 * {@link AsciiArtEmailNotificationService} and
 * {@link HtmlEmailNotificationService}, which otherwise render completely
 * differently but need the exact same date/time formatting.
 */
final class EmailSlotFormatter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.of("pt", "PT"));
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

    private EmailSlotFormatter() {
    }

    static String formatSlot(Appointment appointment) {
        return DATE_TIME_FORMATTER.format(appointment.getStartsAt().atZoneSameInstant(LISBON));
    }
}
