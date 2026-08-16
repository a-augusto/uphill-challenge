package com.uphill.appointments.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.uphill.appointments.domain.Appointment;
import com.uphill.appointments.domain.Doctor;
import com.uphill.appointments.domain.PatientInfo;
import com.uphill.appointments.domain.Room;
import com.uphill.appointments.domain.Specialty;
import com.uphill.appointments.repository.AppointmentRepository;
import com.uphill.appointments.repository.DoctorRepository;
import com.uphill.appointments.repository.RoomRepository;
import com.uphill.appointments.repository.SpecialtyRepository;

import lombok.RequiredArgsConstructor;

/**
 * Assigns a doctor + room for a requested specialty/timeslot and persists the
 * booking. No-overbooking is guaranteed by the DB unique constraints on
 * (doctor_id, starts_at) and (room_id, starts_at) — this class does not rely
 * on locking for correctness, only retries past races it loses.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    static final Duration SLOT_DURATION = Duration.ofMinutes(30);
    private static final int MAX_BOOKING_ATTEMPTS = 20;

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingAttemptExecutor bookingAttemptExecutor;

    public Appointment book(String specialtyCode, PatientInfo patient, Instant startsAt) {
        Specialty specialty = specialtyRepository.findByCode(specialtyCode)
                .orElseThrow(() -> new SlotValidationException("Unknown specialty code: " + specialtyCode));
        validateSlot(startsAt);
        Instant endsAt = startsAt.plus(SLOT_DURATION);

        List<Doctor> availableDoctors = availableDoctors(specialty, startsAt);
        List<Room> availableRooms = availableRooms(startsAt);
        if (availableDoctors.isEmpty() || availableRooms.isEmpty()) {
            throw new AppointmentAllocationException(
                    "No available doctor/room for specialty " + specialtyCode + " at " + startsAt);
        }

        return tryBookAny(availableDoctors, availableRooms, specialty, patient, startsAt, endsAt);
    }

    private Appointment tryBookAny(
            List<Doctor> doctors, List<Room> rooms, Specialty specialty,
            PatientInfo patient, Instant startsAt, Instant endsAt) {
        int attempts = 0;
        for (Doctor doctor : doctors) {
            for (Room room : rooms) {
                if (attempts >= MAX_BOOKING_ATTEMPTS) {
                    throw new AppointmentAllocationException(
                            "No available doctor/room for specialty " + specialty.getCode() + " at " + startsAt);
                }
                attempts++;
                try {
                    return bookingAttemptExecutor.attemptBook(doctor, room, specialty, patient, startsAt, endsAt);
                } catch (DataIntegrityViolationException lostRace) {
                    // Another request took this doctor or room for this slot first — try the next pair.
                }
            }
        }
        throw new AppointmentAllocationException(
                "No available doctor/room for specialty " + specialty.getCode() + " at " + startsAt);
    }

    private List<Doctor> availableDoctors(Specialty specialty, Instant startsAt) {
        List<Doctor> doctors = doctorRepository.findBySpecialtyAndActiveTrue(specialty);
        List<Long> doctorIds = doctors.stream().map(Doctor::getId).toList();
        Set<Long> booked = doctorIds.isEmpty()
                ? Set.of()
                : new HashSet<>(appointmentRepository.findBookedDoctorIdsAtSlot(startsAt, doctorIds));
        List<Doctor> available = new ArrayList<>(doctors.stream().filter(d -> !booked.contains(d.getId())).toList());
        Collections.shuffle(available);
        return available;
    }

    private List<Room> availableRooms(Instant startsAt) {
        List<Room> rooms = roomRepository.findByActiveTrue();
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();
        Set<Long> booked = roomIds.isEmpty()
                ? Set.of()
                : new HashSet<>(appointmentRepository.findBookedRoomIdsAtSlot(startsAt, roomIds));
        List<Room> available = new ArrayList<>(rooms.stream().filter(r -> !booked.contains(r.getId())).toList());
        Collections.shuffle(available);
        return available;
    }

    private void validateSlot(Instant startsAt) {
        if (!startsAt.isAfter(Instant.now())) {
            throw new SlotValidationException("startsAt must be in the future");
        }
        if (startsAt.getEpochSecond() % SLOT_DURATION.getSeconds() != 0) {
            throw new SlotValidationException(
                    "startsAt must align to a " + SLOT_DURATION.toMinutes() + "-minute slot boundary");
        }
    }
}
