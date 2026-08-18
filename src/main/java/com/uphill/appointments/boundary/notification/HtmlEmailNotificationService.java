package com.uphill.appointments.boundary.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.uphill.appointments.entity.Appointment;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends confirmation/cancellation emails as HTML, rendered from the
 * templates under {@code templates/email/} - active only in the
 * {@code prod} profile. See {@link AsciiArtEmailNotificationService} for
 * every other environment. Locally and in tests this points at a fake SMTP
 * server (GreenMail); the code path is real either way, only the target
 * server differs by config.
 */
@Service
@Profile("prod")
@Slf4j
public class HtmlEmailNotificationService implements NotificationService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String fromAddress;

    public HtmlEmailNotificationService(
            JavaMailSender mailSender, TemplateEngine templateEngine, @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendAppointmentConfirmation(Appointment appointment) {
        send(appointment, "Your appointment is confirmed", "email/appointment-confirmation");
        log.info("Sent appointment confirmation email for appointment {}", appointment.getId());
    }

    @Override
    public void sendAppointmentCancellation(Appointment appointment) {
        send(appointment, "Your appointment has been cancelled", "email/appointment-cancellation");
        log.info("Sent appointment cancellation email for appointment {}", appointment.getId());
    }

    private void send(Appointment appointment, String subject, String templateName) {
        Context context = new Context();
        context.setVariable("patientName", appointment.getPatient().getName());
        context.setVariable("specialty", appointment.getSpecialty().getName());
        context.setVariable("doctorName", appointment.getDoctor().getName());
        context.setVariable("roomName", appointment.getRoom().getName());
        context.setVariable("slot", EmailSlotFormatter.formatSlot(appointment));
        String html = templateEngine.process(templateName, context);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(appointment.getPatient().getEmail());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(mimeMessage);
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to build email for appointment " + appointment.getId(), e);
        }
    }
}
