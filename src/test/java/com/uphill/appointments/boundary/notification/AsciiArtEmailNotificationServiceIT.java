package com.uphill.appointments.boundary.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.AppointmentStatus;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;

import jakarta.mail.internet.MimeMessage;

/**
 * Exercises the real JavaMailSender code path end-to-end against GreenMail
 * (a fake SMTP server) — proves the abstraction is genuinely wired, not a
 * no-op, without needing real SMTP credentials.
 */
class AsciiArtEmailNotificationServiceIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sendsConfirmationEmailWithAsciiBannerAndDetails() throws Exception {
        AsciiArtEmailNotificationService service = service();

        Appointment appointment = sampleAppointment();
        service.sendAppointmentConfirmation(appointment);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("jane@example.com");
        String body = (String) received[0].getContent();
        assertThat(body).contains("UPHILL HEALTH");
        assertThat(body).contains("Appointment Confirmed");
        assertThat(body).contains("╔");
        assertThat(body).contains("Dr. Ana Ferreira");
        assertThat(body).contains("Room 1");
    }

    @Test
    void sendsCancellationEmailWithAsciiBannerAndDetails() throws Exception {
        AsciiArtEmailNotificationService service = service();

        Appointment appointment = sampleAppointment();
        appointment.setStatus(AppointmentStatus.CANCELLED);
        service.sendAppointmentCancellation(appointment);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        String body = (String) received[0].getContent();
        assertThat(body).contains("Appointment Cancelled");
        assertThat(body).contains("Dr. Ana Ferreira");
        assertThat(body).contains("Room 1");
    }

    private static AsciiArtEmailNotificationService service() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());
        return new AsciiArtEmailNotificationService(mailSender, "appointments@uphill.health");
    }

    private static Appointment sampleAppointment() {
        Specialty specialty = new Specialty(1L, "CARDIOLOGY", "Cardiology");
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        doctor.setName("Dr. Ana Ferreira");
        doctor.setSpecialty(specialty);
        Room room = new Room();
        room.setId(1L);
        room.setName("Room 1");

        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setPatientId("PAT-0001");
        patient.setName("Jane Doe");
        patient.setEmail("jane@example.com");
        appointment.setPatient(patient);
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        OffsetDateTime startsAt = OffsetDateTime.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        return appointment;
    }
}
