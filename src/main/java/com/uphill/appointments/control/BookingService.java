package com.uphill.appointments.control;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Assigns a doctor + room for a requested specialty/timeslot and persists the
 * booking. No-overbooking is guaranteed by the DB unique constraints on
 * (doctor_id, starts_at) and (room_id, starts_at) — this class does not rely
 * on locking for correctness, only retries past races it loses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    static final Duration SLOT_DURATION = Duration.ofMinutes(30);
    private static final int MAX_BOOKING_ATTEMPTS = 20;

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final RoomAvailabilityService roomAvailabilityService;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingAttemptExecutor bookingAttemptExecutor;

    public Appointment book(String specialtyCode, String patientId, OffsetDateTime startsAt) {
        Specialty specialty = specialtyRepository.findByCode(specialtyCode)
                .orElseThrow(() -> new SlotValidationException("Unknown specialty code: " + specialtyCode));
        Patient patient = patientRepository.findByPatientId(patientId)
                .orElseThrow(() -> new PatientNotFoundException("Unknown patientId: " + patientId));
        validateSlot(startsAt);
        OffsetDateTime endsAt = startsAt.plus(SLOT_DURATION);

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
            Patient patient, OffsetDateTime startsAt, OffsetDateTime endsAt) {
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
                    // Another request took this doctor or room for this slot first — routine, silent.
                } catch (RoomReservationFailedException roomFailure) {
                    // Worth a log line (unlike a routine DB race): the external room system
                    // rejecting/erroring is an operational signal, not expected noise.
                    log.warn(
                            "Room {} reservation failed for specialty {} at {}, trying next candidate",
                            room.getId(), specialty.getCode(), startsAt, roomFailure);
                }
            }
        }
        throw new AppointmentAllocationException(
                "No available doctor/room for specialty " + specialty.getCode() + " at " + startsAt);
    }

    private List<Doctor> availableDoctors(Specialty specialty, OffsetDateTime startsAt) {
        List<Doctor> doctors = doctorRepository.findBySpecialtyAndActiveTrue(specialty);
        List<Long> doctorIds = doctors.stream().map(Doctor::getId).toList();
        Set<Long> booked = doctorIds.isEmpty()
                ? Set.of()
                : new HashSet<>(appointmentRepository.findBookedDoctorIdsAtSlot(startsAt, doctorIds));
        List<Doctor> available = new ArrayList<>(doctors.stream().filter(d -> !booked.contains(d.getId())).toList());
        Collections.shuffle(available);
        return available;
    }

    private List<Room> availableRooms(OffsetDateTime startsAt) {
        List<Room> rooms;
        try {
            rooms = roomAvailabilityService.availableRoomsOn(startsAt.toLocalDate());
        } catch (RoomAvailabilityCheckFailedException e) {
            throw new AppointmentAllocationException("Unable to determine room availability: " + e.getMessage());
        }
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();
        Set<Long> booked = roomIds.isEmpty()
                ? Set.of()
                : new HashSet<>(appointmentRepository.findBookedRoomIdsAtSlot(startsAt, roomIds));
        List<Room> available = new ArrayList<>(rooms.stream().filter(r -> !booked.contains(r.getId())).toList());
        Collections.shuffle(available);
        return available;
    }

    private void validateSlot(OffsetDateTime startsAt) {
        if (!startsAt.isAfter(OffsetDateTime.now())) {
            throw new SlotValidationException("startsAt must be in the future");
        }
        if (startsAt.toEpochSecond() % SLOT_DURATION.getSeconds() != 0) {
            throw new SlotValidationException(
                    "startsAt must align to a " + SLOT_DURATION.toMinutes() + "-minute slot boundary");
        }
    }
}
