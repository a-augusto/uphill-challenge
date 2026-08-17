package com.uphill.appointments.boundary.notification;

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
 * Sends the confirmation email via {@link JavaMailSender}. Locally and in
 * tests this points at a fake SMTP server (GreenMail); in production it would
 * point at the real relay/SES/SendGrid SMTP endpoint. The code path is real
 * either way — only the target server differs by config.
 */
@Service
@Slf4j
public class EmailNotificationService implements NotificationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.of("pt", "PT"));

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendAppointmentConfirmation(Appointment appointment) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(appointment.getPatient().getEmail());
        message.setSubject("Your appointment is confirmed");
        message.setText("""
                Hi %s,

                Your appointment has been confirmed for %s.
                Doctor: %s
                Room: %s

                See you soon!
                """.formatted(
                appointment.getPatient().getName(),
                DATE_TIME_FORMATTER.format(
                        appointment.getStartsAt().atZoneSameInstant(java.time.ZoneId.of("Europe/Lisbon"))),
                appointment.getDoctor().getName(),
                appointment.getRoom().getName()));

        mailSender.send(message);
        log.info("Sent appointment confirmation email for appointment {}", appointment.getId());
    }
}
