package com.uphill.appointments.boundary.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.uphill.appointments.entity.Appointment;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends confirmation/cancellation emails via {@link JavaMailSender}. Locally
 * and in tests this points at a fake SMTP server (GreenMail); in production
 * it would point at the real relay/SES/SendGrid SMTP endpoint. The code path
 * is real either way — only the target server differs by config.
 */
@Service
@Slf4j
public class EmailNotificationService implements NotificationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.of("pt", "PT"));
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendAppointmentConfirmation(Appointment appointment) {
        send(appointment, "Your appointment is confirmed", """
                Hi %s,

                Your appointment has been confirmed for %s.
                Doctor: %s
                Room: %s

                See you soon!
                """.formatted(
                appointment.getPatient().getName(),
                formatSlot(appointment),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName()));
        log.info("Sent appointment confirmation email for appointment {}", appointment.getId());
    }

    @Override
    public void sendAppointmentCancellation(Appointment appointment) {
        send(appointment, "Your appointment has been cancelled", """
                Hi %s,

                Your appointment for %s has been cancelled.
                Doctor: %s
                Room: %s

                If this wasn't you, please get in touch.
                """.formatted(
                appointment.getPatient().getName(),
                formatSlot(appointment),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName()));
        log.info("Sent appointment cancellation email for appointment {}", appointment.getId());
    }

    private void send(Appointment appointment, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(appointment.getPatient().getEmail());
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    private static String formatSlot(Appointment appointment) {
        return DATE_TIME_FORMATTER.format(appointment.getStartsAt().atZoneSameInstant(LISBON));
    }
}
