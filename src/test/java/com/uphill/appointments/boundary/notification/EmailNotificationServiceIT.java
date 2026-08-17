package com.uphill.appointments.boundary.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.PatientInfo;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;

import jakarta.mail.internet.MimeMessage;

/**
 * Exercises the real JavaMailSender code path end-to-end against GreenMail
 * (a fake SMTP server) — proves the abstraction is genuinely wired, not a
 * no-op, without needing real SMTP credentials.
 */
class EmailNotificationServiceIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sendsConfirmationEmailWithDoctorAndRoomDetails() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());
        EmailNotificationService service = new EmailNotificationService(mailSender, "appointments@uphill.health");

        Appointment appointment = sampleAppointment();
        service.sendAppointmentConfirmation(appointment);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("jane@example.com");
        String body = GreenMailUtil.getBody(received[0]);
        assertThat(body).contains("Dr. Ana Ferreira");
        assertThat(body).contains("Room 1");
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
        appointment.setPatient(new PatientInfo("Jane Doe", "jane@example.com", "912345678"));
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        Instant startsAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        return appointment;
    }
}
