package com.uphill.appointments.seed;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Gender;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.RoomRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

/**
 * Populates specialties/doctors/rooms/patients for local development and
 * demos. Deliberately never runs by default — activate with the "seed"
 * profile ({@code --spring.profiles.active=seed}) so it can never fire
 * against a real environment by accident. Idempotent: skips entirely if any
 * specialties already exist.
 *
 * <p>Specialties are real domain vocabulary (hardcoded); doctors, rooms and
 * patients are demo/mock fixtures (generated via DataFaker) — Flyway only
 * owns structure, this owns rows.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    private static final Map<String, String> SPECIALTIES = Map.of(
            "CARDIOLOGY", "Cardiology",
            "DERMATOLOGY", "Dermatology",
            "GENERAL_PRACTICE", "General Practice",
            "PEDIATRICS", "Pediatrics");
    private static final int DOCTORS_PER_SPECIALTY = 2;
    private static final int ROOM_COUNT = 5;
    private static final int PATIENT_COUNT = 30;
    private static final List<String> LANGUAGE_POOL = List.of("pt", "en", "es", "fr", "de");

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final PatientRepository patientRepository;

    @Override
    public void run(String... args) {
        if (specialtyRepository.count() > 0) {
            log.info("Seed data already present — skipping (DevDataSeeder is idempotent).");
            return;
        }

        Faker faker = new Faker();
        List<Specialty> specialties = seedSpecialties();
        int doctorCount = seedDoctors(faker, specialties);
        seedRooms();
        seedPatients(faker);

        log.info(
                "Seeded {} specialties, {} doctors, {} rooms, {} patients.",
                specialties.size(), doctorCount, ROOM_COUNT, PATIENT_COUNT);
    }

    private List<Specialty> seedSpecialties() {
        List<Specialty> specialties = new ArrayList<>();
        SPECIALTIES.forEach((code, name) -> {
            Specialty specialty = new Specialty();
            specialty.setCode(code);
            specialty.setName(name);
            specialties.add(specialtyRepository.save(specialty));
        });
        return specialties;
    }

    private int seedDoctors(Faker faker, List<Specialty> specialties) {
        int count = 0;
        for (Specialty specialty : specialties) {
            for (int i = 0; i < DOCTORS_PER_SPECIALTY; i++) {
                Doctor doctor = new Doctor();
                doctor.setName("Dr. " + faker.name().fullName());
                doctor.setSpecialty(specialty);
                doctor.setActive(true);
                doctorRepository.save(doctor);
                count++;
            }
        }
        return count;
    }

    private void seedRooms() {
        for (int i = 1; i <= ROOM_COUNT; i++) {
            Room room = new Room();
            room.setName("Room " + i);
            room.setActive(true);
            roomRepository.save(room);
        }
    }

    private void seedPatients(Faker faker) {
        Random random = new Random();
        Gender[] genders = Gender.values();
        for (int i = 1; i <= PATIENT_COUNT; i++) {
            Patient patient = new Patient();
            patient.setPatientId("PAT-%06d".formatted(i));
            patient.setName(faker.name().fullName());
            patient.setEmail(faker.internet().emailAddress());
            patient.setPhone(faker.phoneNumber().phoneNumber());
            patient.setGender(genders[random.nextInt(genders.length)]);
            patient.setDateOfBirth(toLocalDate(faker.date().birthday(1, 95)));
            patient.setAddress(faker.address().fullAddress());
            patient.setEmergencyContactPhone(faker.phoneNumber().phoneNumber());
            patient.setLanguagesSpoken(randomLanguages(random));
            patientRepository.save(patient);
        }
    }

    private static LocalDate toLocalDate(java.util.Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static List<String> randomLanguages(Random random) {
        List<String> shuffled = new ArrayList<>(LANGUAGE_POOL);
        Collections.shuffle(shuffled, random);
        int count = 1 + random.nextInt(3);
        return new ArrayList<>(shuffled.subList(0, Math.min(count, shuffled.size())));
    }
}
