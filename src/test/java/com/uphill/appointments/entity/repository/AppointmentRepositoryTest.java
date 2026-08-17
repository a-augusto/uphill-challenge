package com.uphill.appointments.entity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.PatientInfo;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Verifies the DB-level unique constraints actually reject double-booking —
 * the real safety mechanism BookingService's retry loop leans on. Uses a real
 * Postgres (via Testcontainers), not H2, because these are Postgres-specific
 * unique-index semantics.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
class AppointmentRepositoryTest {

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Test
    void rejectsSecondAppointmentForSameDoctorAtSameSlot() {
        Specialty cardiology = specialtyRepository.findByCode("CARDIOLOGY").orElseThrow();
        Doctor doctor = doctorRepository.findBySpecialtyAndActiveTrue(cardiology).getFirst();
        Room room1 = roomRepository.findByActiveTrue().get(0);
        Room room2 = roomRepository.findByActiveTrue().get(1);
        Instant startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(cardiology, doctor, room1, startsAt));

        Appointment conflicting = newAppointment(cardiology, doctor, room2, startsAt);
        assertThatThrownBy(() -> appointmentRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondAppointmentForSameRoomAtSameSlot() {
        Specialty cardiology = specialtyRepository.findByCode("CARDIOLOGY").orElseThrow();
        Doctor doctorA = doctorRepository.findBySpecialtyAndActiveTrue(cardiology).get(0);
        Doctor doctorB = doctorRepository.findBySpecialtyAndActiveTrue(cardiology).get(1);
        Room room = roomRepository.findByActiveTrue().getFirst();
        Instant startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(cardiology, doctorA, room, startsAt));

        Appointment conflicting = newAppointment(cardiology, doctorB, room, startsAt);
        assertThatThrownBy(() -> appointmentRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsDifferentDoctorAndRoomAtSameSlot() {
        Specialty cardiology = specialtyRepository.findByCode("CARDIOLOGY").orElseThrow();
        Doctor doctorA = doctorRepository.findBySpecialtyAndActiveTrue(cardiology).get(0);
        Doctor doctorB = doctorRepository.findBySpecialtyAndActiveTrue(cardiology).get(1);
        Room room1 = roomRepository.findByActiveTrue().get(0);
        Room room2 = roomRepository.findByActiveTrue().get(1);
        Instant startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(cardiology, doctorA, room1, startsAt));
        Appointment second = appointmentRepository.saveAndFlush(newAppointment(cardiology, doctorB, room2, startsAt));

        assertThat(second.getId()).isNotNull();
    }

    private static Appointment newAppointment(Specialty specialty, Doctor doctor, Room room, Instant startsAt) {
        Appointment appointment = new Appointment();
        appointment.setPatient(new PatientInfo("Jane Doe", "jane@example.com", "912345678"));
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        return appointment;
    }

    private static Instant futureSlot() {
        return Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }
}
