package com.uphill.appointments.entity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.support.TestDataFactory;
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
    @Autowired
    private PatientRepository patientRepository;

    private TestDataFactory fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new TestDataFactory(specialtyRepository, doctorRepository, roomRepository, patientRepository);
    }

    @Test
    void rejectsSecondAppointmentForSameDoctorAtSameSlot() {
        Specialty specialty = fixtures.createSpecialty();
        Patient patient = fixtures.createPatient();
        Doctor doctor = fixtures.createDoctor(specialty);
        Room room1 = fixtures.createRoom();
        Room room2 = fixtures.createRoom();
        OffsetDateTime startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(patient, specialty, doctor, room1, startsAt));

        Appointment conflicting = newAppointment(patient, specialty, doctor, room2, startsAt);
        assertThatThrownBy(() -> appointmentRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondAppointmentForSameRoomAtSameSlot() {
        Specialty specialty = fixtures.createSpecialty();
        Patient patient = fixtures.createPatient();
        Doctor doctorA = fixtures.createDoctor(specialty);
        Doctor doctorB = fixtures.createDoctor(specialty);
        Room room = fixtures.createRoom();
        OffsetDateTime startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(patient, specialty, doctorA, room, startsAt));

        Appointment conflicting = newAppointment(patient, specialty, doctorB, room, startsAt);
        assertThatThrownBy(() -> appointmentRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsDifferentDoctorAndRoomAtSameSlot() {
        Specialty specialty = fixtures.createSpecialty();
        Patient patient = fixtures.createPatient();
        Doctor doctorA = fixtures.createDoctor(specialty);
        Doctor doctorB = fixtures.createDoctor(specialty);
        Room room1 = fixtures.createRoom();
        Room room2 = fixtures.createRoom();
        OffsetDateTime startsAt = futureSlot();

        appointmentRepository.saveAndFlush(newAppointment(patient, specialty, doctorA, room1, startsAt));
        Appointment second =
                appointmentRepository.saveAndFlush(newAppointment(patient, specialty, doctorB, room2, startsAt));

        assertThat(second.getId()).isNotNull();
    }

    private static Appointment newAppointment(
            Patient patient, Specialty specialty, Doctor doctor, Room room, OffsetDateTime startsAt) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        return appointment;
    }

    private static OffsetDateTime futureSlot() {
        return OffsetDateTime.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }
}
