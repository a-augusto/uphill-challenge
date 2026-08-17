package com.uphill.appointments.support;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Gender;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.RoomRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

/**
 * Persists minimal, uniquely-named fixtures for Testcontainers-backed tests
 * via the real repositories — decoupled from both Flyway (structure only)
 * and the DataFaker-based dev seeder (which never runs in tests).
 */
public class TestDataFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final PatientRepository patientRepository;

    public TestDataFactory(
            SpecialtyRepository specialtyRepository, DoctorRepository doctorRepository,
            RoomRepository roomRepository, PatientRepository patientRepository) {
        this.specialtyRepository = specialtyRepository;
        this.doctorRepository = doctorRepository;
        this.roomRepository = roomRepository;
        this.patientRepository = patientRepository;
    }

    public Specialty createSpecialty() {
        int n = COUNTER.incrementAndGet();
        Specialty specialty = new Specialty();
        specialty.setCode("TEST_SPECIALTY_" + n);
        specialty.setName("Test Specialty " + n);
        return specialtyRepository.save(specialty);
    }

    public Doctor createDoctor(Specialty specialty) {
        Doctor doctor = new Doctor();
        doctor.setName("Dr. Test " + COUNTER.incrementAndGet());
        doctor.setSpecialty(specialty);
        doctor.setActive(true);
        return doctorRepository.save(doctor);
    }

    public Room createRoom() {
        Room room = new Room();
        room.setName("Test Room " + COUNTER.incrementAndGet());
        room.setActive(true);
        return roomRepository.save(room);
    }

    public Patient createPatient() {
        int n = COUNTER.incrementAndGet();
        Patient patient = new Patient();
        patient.setPatientId("TEST-PAT-" + n);
        patient.setName("Test Patient " + n);
        patient.setEmail("test.patient." + n + "@example.com");
        patient.setPhone("912345678");
        patient.setGender(Gender.UNSPECIFIED);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setAddress("Test Address");
        patient.setEmergencyContactPhone("912345679");
        return patientRepository.save(patient);
    }
}
