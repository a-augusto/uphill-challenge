package com.uphill.appointments.entity.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.DoctorSchedule;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.support.TestDataFactory;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Verifies the DB-level CHECK constraint rejects a reversed schedule range —
 * nothing at the application layer validates this today (no admin API
 * creates schedules), so the constraint is the only guardrail.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
class DoctorScheduleRepositoryTest {

    @Autowired
    private DoctorScheduleRepository doctorScheduleRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private SpecialtyRepository specialtyRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private PatientRepository patientRepository;

    @Test
    void rejectsScheduleWhereStartTimeIsNotBeforeEndTime() {
        TestDataFactory fixtures =
                new TestDataFactory(specialtyRepository, doctorRepository, doctorScheduleRepository, roomRepository, patientRepository);
        Specialty specialty = fixtures.createSpecialty();
        Doctor doctor = new Doctor();
        doctor.setName("Dr. Reversed");
        doctor.setSpecialty(specialty);
        doctor.setActive(true);
        doctor = doctorRepository.saveAndFlush(doctor);

        DoctorSchedule reversed = new DoctorSchedule();
        reversed.setDoctor(doctor);
        reversed.setDayOfWeek(DayOfWeek.MONDAY);
        reversed.setStartTime(LocalTime.of(18, 0));
        reversed.setEndTime(LocalTime.of(9, 0));

        assertThatThrownBy(() -> doctorScheduleRepository.saveAndFlush(reversed))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
