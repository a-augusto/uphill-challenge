package com.uphill.appointments.control;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.uphill.appointments.control.exceptions.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.DoctorSchedule;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.DoctorScheduleRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Assigns a doctor + room for a requested specialty/timeslot and persists the
 * booking. No-overbooking is guaranteed by a DB range-exclusion constraint on
 * overlapping (doctor_id, [starts_at, ends_at)) and (room_id, [starts_at,
 * ends_at)) — this class does not rely on locking for correctness, only
 * retries past races it loses.
 *
 * <p>Two entry points: {@link #book} for an explicit start time, and
 * {@link #bookOnDay} which searches for a free doctor/room/time within
 * extended business hours (9am-6pm) when the caller only supplies a day.
 * Both ultimately build a list of {@link Candidate}s and hand them to
 * {@link #tryBookCandidates}, which owns the one shared piece of retry logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    static final Duration GRID = Duration.ofMinutes(15);
    static final Duration DEFAULT_DURATION = Duration.ofMinutes(30);
    static final Duration MIN_DURATION = Duration.ofMinutes(15);
    static final Duration MAX_DURATION = Duration.ofHours(8);
    static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    static final LocalTime WINDOW_END = LocalTime.of(18, 0);
    private static final int MAX_BOOKING_ATTEMPTS = 20;
    private static final int CONSECUTIVE_ROOM_FAILURE_THRESHOLD = 3;

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final RoomAvailabilityService roomAvailabilityService;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingAttemptExecutor bookingAttemptExecutor;
    private final MeterRegistry meterRegistry;

    private record Candidate(Doctor doctor, Room room, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    public Appointment book(String specialtyCode, String patientId, OffsetDateTime startsAt, Integer durationMinutes) {
        Specialty specialty = specialtyRepository.findByCode(specialtyCode)
                .orElseThrow(() -> new SlotValidationException("Unknown specialty code: " + specialtyCode));
        Patient patient = patientRepository.findByPatientId(patientId)
                .orElseThrow(() -> new PatientNotFoundException("Unknown patientId: " + patientId));
        Duration duration = resolveDuration(durationMinutes);
        validateSlot(startsAt, duration);
        OffsetDateTime endsAt = startsAt.plus(duration);
        if (!endsAt.toLocalDate().equals(startsAt.toLocalDate())) {
            throw new SlotValidationException("Appointments cannot cross midnight");
        }

        List<Doctor> availableDoctors = availableDoctors(specialty, startsAt, endsAt);
        List<Room> availableRooms = availableRooms(startsAt, endsAt);
        if (availableDoctors.isEmpty() || availableRooms.isEmpty()) {
            recordBookingFailure("no_availability");
            throw new AppointmentAllocationException(
                    "No available doctor/room for specialty " + specialtyCode + " at " + startsAt);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Doctor doctor : availableDoctors) {
            for (Room room : availableRooms) {
                candidates.add(new Candidate(doctor, room, startsAt, endsAt));
            }
        }
        return tryBookCandidates(candidates, specialty, patient,
                "No available doctor/room for specialty " + specialtyCode + " at " + startsAt);
    }

    /**
     * Searches for a free doctor/room/time within extended business hours
     * (9am-6pm) on the given day, per #023/#025 — fails rather than silently
     * expanding outside that window if nothing fits, even if the day has
     * capacity elsewhere.
     */
    public Appointment bookOnDay(
            String specialtyCode, String patientId, LocalDate date, ZoneOffset offset, Integer durationMinutes) {
        Specialty specialty = specialtyRepository.findByCode(specialtyCode)
                .orElseThrow(() -> new SlotValidationException("Unknown specialty code: " + specialtyCode));
        Patient patient = patientRepository.findByPatientId(patientId)
                .orElseThrow(() -> new PatientNotFoundException("Unknown patientId: " + patientId));
        Duration duration = resolveDuration(durationMinutes);
        validateDuration(duration);

        OffsetDateTime now = OffsetDateTime.now(offset);
        if (date.isBefore(now.toLocalDate())) {
            throw new SlotValidationException("date must not be in the past");
        }

        OffsetDateTime windowStart = effectiveWindowStart(date, offset, now);
        OffsetDateTime windowEnd = OffsetDateTime.of(date, WINDOW_END, offset);
        String noAvailabilityMessage = "No available doctor/room for specialty " + specialtyCode + " on " + date;
        if (!windowStart.isBefore(windowEnd)) {
            recordBookingFailure("no_availability");
            throw new AppointmentAllocationException(noAvailabilityMessage);
        }

        List<Candidate> candidates = dayOnlyCandidates(specialty, new Interval(windowStart, windowEnd), duration);
        if (candidates.isEmpty()) {
            recordBookingFailure("no_availability");
            throw new AppointmentAllocationException(noAvailabilityMessage);
        }
        return tryBookCandidates(candidates, specialty, patient, noAvailabilityMessage);
    }

    private OffsetDateTime effectiveWindowStart(LocalDate date, ZoneOffset offset, OffsetDateTime now) {
        OffsetDateTime fixedStart = OffsetDateTime.of(date, WINDOW_START, offset);
        if (!date.equals(now.toLocalDate())) {
            return fixedStart;
        }
        OffsetDateTime ceilNow = ceilToGrid(now);
        if (!ceilNow.isAfter(now)) {
            ceilNow = ceilNow.plusSeconds(GRID.getSeconds());
        }
        return ceilNow.isAfter(fixedStart) ? ceilNow : fixedStart;
    }

    /**
     * Rounds up to the next 15-minute grid line. Free-window bounds are
     * normally already grid-aligned by construction (appointments, the
     * business window, "now" above) — the one exception is a
     * {@link DoctorSchedule} row, which nothing currently validates as
     * grid-aligned. Snapping here means a misaligned schedule can't produce
     * a misaligned booking; {@link #dayOnlyCandidates} re-checks the
     * duration still fits after snapping, since rounding up can eat into a
     * narrow gap.
     */
    private static OffsetDateTime ceilToGrid(OffsetDateTime time) {
        long gridSeconds = GRID.getSeconds();
        long remainder = time.toEpochSecond() % gridSeconds;
        return remainder == 0 ? time : time.plusSeconds(gridSeconds - remainder);
    }

    private List<Candidate> dayOnlyCandidates(Specialty specialty, Interval window, Duration duration) {
        List<Doctor> doctors = doctorRepository.findBySpecialtyAndActiveTrue(specialty);
        List<Long> doctorIds = doctors.stream().map(Doctor::getId).toList();
        DayOfWeek dayOfWeek = window.start().getDayOfWeek();
        Map<Long, DoctorSchedule> scheduleByDoctor = doctorIds.isEmpty()
                ? Map.of()
                : doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(doctorIds, dayOfWeek).stream()
                        .collect(Collectors.toMap(s -> s.getDoctor().getId(), s -> s));

        List<Room> rooms;
        try {
            rooms = roomAvailabilityService.availableRoomsOn(window.start());
        } catch (RoomAvailabilityCheckFailedException e) {
            recordBookingFailure("external_check_failed");
            log.warn("Room availability check failed for {}", window.start(), e);
            throw new AppointmentAllocationException("Unable to determine room availability: " + e.getMessage(), e);
        }
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();

        Map<Long, List<Interval>> busyByDoctor = doctorIds.isEmpty()
                ? Map.of()
                : appointmentRepository
                        .findBookedAppointmentsForDoctorsOverlapping(doctorIds, window.start(), window.end()).stream()
                        .collect(Collectors.groupingBy(a -> a.getDoctor().getId(),
                                Collectors.mapping(a -> new Interval(a.getStartsAt(), a.getEndsAt()), Collectors.toList())));
        Map<Long, List<Interval>> busyByRoom = roomIds.isEmpty()
                ? Map.of()
                : appointmentRepository
                        .findBookedAppointmentsForRoomsOverlapping(roomIds, window.start(), window.end()).stream()
                        .collect(Collectors.groupingBy(a -> a.getRoom().getId(),
                                Collectors.mapping(a -> new Interval(a.getStartsAt(), a.getEndsAt()), Collectors.toList())));

        List<Doctor> shuffledDoctors = new ArrayList<>(doctors);
        Collections.shuffle(shuffledDoctors);
        List<Room> shuffledRooms = new ArrayList<>(rooms);
        Collections.shuffle(shuffledRooms);

        List<Candidate> candidates = new ArrayList<>();
        for (Doctor doctor : shuffledDoctors) {
            DoctorSchedule schedule = scheduleByDoctor.get(doctor.getId());
            if (schedule == null) {
                continue;
            }
            OffsetDateTime doctorStart = laterOf(window.start(),
                    OffsetDateTime.of(window.start().toLocalDate(), schedule.getStartTime(), window.start().getOffset()));
            OffsetDateTime doctorEnd = earlierOf(window.end(),
                    OffsetDateTime.of(window.start().toLocalDate(), schedule.getEndTime(), window.start().getOffset()));
            if (!doctorStart.isBefore(doctorEnd)) {
                continue;
            }
            List<Interval> doctorFree = FreeWindowFinder.freeWindows(
                    new Interval(doctorStart, doctorEnd), busyByDoctor.getOrDefault(doctor.getId(), List.of()));
            for (Room room : shuffledRooms) {
                List<Interval> roomFree =
                        FreeWindowFinder.freeWindows(window, busyByRoom.getOrDefault(room.getId(), List.of()));
                FreeWindowFinder.intersect(doctorFree, roomFree).stream()
                        .map(i -> new Interval(ceilToGrid(i.start()), i.end()))
                        .filter(i -> !i.start().isAfter(i.end())
                                && Duration.between(i.start(), i.end()).compareTo(duration) >= 0)
                        .findFirst()
                        .ifPresent(fit -> candidates.add(new Candidate(doctor, room, fit.start(), fit.start().plus(duration))));
            }
        }
        return candidates;
    }

    private static OffsetDateTime laterOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static OffsetDateTime earlierOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private Appointment tryBookCandidates(
            List<Candidate> candidates, Specialty specialty, Patient patient, String exhaustedMessage) {
        int attempts = 0;
        int consecutiveRoomFailures = 0;
        for (Candidate candidate : candidates) {
            if (attempts >= MAX_BOOKING_ATTEMPTS || consecutiveRoomFailures >= CONSECUTIVE_ROOM_FAILURE_THRESHOLD) {
                break;
            }
            attempts++;
            try {
                return bookingAttemptExecutor.attemptBook(candidate.doctor(), candidate.room(), specialty, patient,
                        candidate.startsAt(), candidate.endsAt());
            } catch (DataIntegrityViolationException | ConcurrencyFailureException lostRace) {
                // Another request took this doctor or room for this slot first (constraint
                // violation), or two concurrent inserts deadlocked while both checked the
                // range-exclusion constraint (ConcurrencyFailureException, e.g. Postgres
                // "deadlock detected") and Postgres aborted this one — either way, routine,
                // silent. Not evidence of external trouble, so it doesn't count toward the
                // failure streak.
                consecutiveRoomFailures = 0;
            } catch (RoomReservationFailedException roomFailure) {
                // Worth a log line (unlike a routine DB race): the external room system
                // rejecting/erroring is an operational signal, not expected noise.
                consecutiveRoomFailures++;
                log.warn(
                        "Room {} reservation failed for specialty {} at {}, trying next candidate",
                        candidate.room().getId(), specialty.getCode(), candidate.startsAt(), roomFailure);
            }
        }
        recordBookingFailure("no_availability");
        throw new AppointmentAllocationException(exhaustedMessage);
    }

    private void recordBookingFailure(String reason) {
        meterRegistry.counter("appointments.booking.failed", "reason", reason).increment();
    }

    private List<Doctor> availableDoctors(Specialty specialty, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        List<Doctor> doctors = doctorRepository.findBySpecialtyAndActiveTrue(specialty);
        List<Long> doctorIds = doctors.stream().map(Doctor::getId).toList();
        if (doctorIds.isEmpty()) {
            return List.of();
        }
        DayOfWeek dayOfWeek = startsAt.getDayOfWeek();
        List<DoctorSchedule> schedules = doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(doctorIds, dayOfWeek);
        Set<Long> onShift = schedules.stream()
                .filter(s -> !startsAt.toLocalTime().isBefore(s.getStartTime())
                        && !endsAt.toLocalTime().isAfter(s.getEndTime()))
                .map(s -> s.getDoctor().getId())
                .collect(Collectors.toSet());
        Set<Long> booked = new HashSet<>(
                appointmentRepository.findBookedDoctorIdsOverlapping(startsAt, endsAt, doctorIds));
        List<Doctor> available = new ArrayList<>(doctors.stream()
                .filter(d -> onShift.contains(d.getId()) && !booked.contains(d.getId()))
                .toList());
        Collections.shuffle(available);
        return available;
    }

    private List<Room> availableRooms(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        List<Room> rooms;
        try {
            rooms = roomAvailabilityService.availableRoomsOn(startsAt);
        } catch (RoomAvailabilityCheckFailedException e) {
            recordBookingFailure("external_check_failed");
            log.warn("Room availability check failed for {}", startsAt, e);
            throw new AppointmentAllocationException("Unable to determine room availability: " + e.getMessage(), e);
        }
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();
        Set<Long> booked = roomIds.isEmpty()
                ? Set.of()
                : new HashSet<>(appointmentRepository.findBookedRoomIdsOverlapping(startsAt, endsAt, roomIds));
        List<Room> available = new ArrayList<>(rooms.stream().filter(r -> !booked.contains(r.getId())).toList());
        Collections.shuffle(available);
        return available;
    }

    private Duration resolveDuration(Integer durationMinutes) {
        return durationMinutes == null ? DEFAULT_DURATION : Duration.ofMinutes(durationMinutes);
    }

    private void validateSlot(OffsetDateTime startsAt, Duration duration) {
        if (!startsAt.isAfter(OffsetDateTime.now())) {
            throw new SlotValidationException("startsAt must be in the future");
        }
        if (startsAt.toEpochSecond() % GRID.getSeconds() != 0) {
            throw new SlotValidationException(
                    "startsAt must align to a " + GRID.toMinutes() + "-minute grid");
        }
        validateDuration(duration);
    }

    private void validateDuration(Duration duration) {
        if (duration.toSeconds() % GRID.getSeconds() != 0) {
            throw new SlotValidationException(
                    "duration must be a multiple of " + GRID.toMinutes() + " minutes");
        }
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw new SlotValidationException(
                    "duration must be between " + MIN_DURATION.toMinutes() + " and "
                            + MAX_DURATION.toMinutes() + " minutes");
        }
    }
}
