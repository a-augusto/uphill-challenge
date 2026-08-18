package com.uphill.appointments.boundary.notification;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.uphill.appointments.entity.Appointment;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends confirmation/cancellation emails as plain text via
 * {@link JavaMailSender} — the default outside the {@code prod} profile
 * (local dev, {@code seed}, tests), where a real HTML inbox rendering
 * doesn't matter and a bit of ASCII-art flair beats a bare "Hi %s,". See
 * {@link HtmlEmailNotificationService} for the {@code prod} counterpart.
 * Locally and in tests this points at a fake SMTP server (GreenMail); the
 * code path is real either way, only the target server differs by config.
 */
@Service
@Profile("!prod")
@Slf4j
public class AsciiArtEmailNotificationService implements NotificationService {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public AsciiArtEmailNotificationService(JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendAppointmentConfirmation(Appointment appointment) {
        String banner = asciiBox("UPHILL HEALTH", "Appointment Confirmed");
        send(appointment, "Your appointment is confirmed", banner + """

                Hi %s,

                Your appointment has been confirmed for %s.
                Doctor: %s
                Room: %s

                See you soon!
                """.formatted(
                appointment.getPatient().getName(),
                EmailSlotFormatter.formatSlot(appointment),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName()));
    }

    @Override
    public void sendAppointmentCancellation(Appointment appointment) {
        String banner = asciiBox("UPHILL HEALTH", "Appointment Cancelled");
        send(appointment, "Your appointment has been cancelled", banner + """

                Hi %s,

                Your appointment for %s has been cancelled.
                Doctor: %s
                Room: %s

                If this wasn't you, please get in touch.
                """.formatted(
                appointment.getPatient().getName(),
                EmailSlotFormatter.formatSlot(appointment),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName()));
    }

    /**
     * Logs the full rendered body at INFO, not just "sent" — the point of
     * this class existing (ASCII art, dev-only) is a fast local feedback
     * loop, and checking GreenMail's web UI (localhost:8082) or an IMAP
     * client for that is slower than just reading the terminal you already
     * have open. {@link HtmlEmailNotificationService} (prod) doesn't do
     * this — nobody wants a rendered HTML email dumped into structured JSON
     * logs, and prod has real inbox access anyway.
     */
    private void send(Appointment appointment, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(appointment.getPatient().getEmail());
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("Sent '{}' to {} for appointment {}:\n{}",
                subject, appointment.getPatient().getEmail(), appointment.getId(), body);
    }

    /**
     * Draws a box around the given lines, sized to the longest one -
     * computed rather than hand-aligned, so it can't drift out of alignment
     * if the banner text ever changes.
     */
    private static String asciiBox(String... lines) {
        int width = Arrays.stream(lines).mapToInt(String::length).max().orElse(0);
        StringBuilder box = new StringBuilder();
        box.append("╔").append("═".repeat(width + 2)).append("╗\n");
        for (String line : lines) {
            box.append("║ ").append(line).append(" ".repeat(width - line.length())).append(" ║\n");
        }
        box.append("╚").append("═".repeat(width + 2)).append("╝");
        return box.toString();
    }
}
