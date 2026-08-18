package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private SpecialtyRepository specialtyRepository;
    @Mock
    private DoctorRepository doctorRepository;
    @Mock
    private DoctorScheduleRepository doctorScheduleRepository;
    @Mock
    private RoomAvailabilityService roomAvailabilityService;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private BookingAttemptExecutor bookingAttemptExecutor;

    private BookingService bookingService;

    private Specialty cardiology;
    private Doctor drA;
    private Doctor drB;
    private Room room1;
    private Room room2;
    private Patient patient;
    private OffsetDateTime startsAt;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                specialtyRepository, doctorRepository, doctorScheduleRepository, roomAvailabilityService,
                patientRepository, appointmentRepository, bookingAttemptExecutor);

        cardiology = new Specialty();
        cardiology.setId(1L);
        cardiology.setCode("CARDIOLOGY");

        drA = new Doctor();
        drA.setId(10L);
        drA.setSpecialty(cardiology);
        drA.setActive(true);

        drB = new Doctor();
        drB.setId(11L);
        drB.setSpecialty(cardiology);
        drB.setActive(true);

        room1 = new Room();
        room1.setId(20L);
        room1.setActive(true);

        room2 = new Room();
        room2.setId(21L);
        room2.setActive(true);

        patient = new Patient();
        patient.setId(1L);
        patient.setPatientId("PAT-0001");
        patient.setName("Jane Doe");
        patient.setEmail("jane@example.com");

        startsAt = OffsetDateTime.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }

    private DoctorSchedule fullDaySchedule(Doctor doctor) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctor(doctor);
        schedule.setDayOfWeek(startsAt.getDayOfWeek());
        schedule.setStartTime(LocalTime.of(0, 0));
        schedule.setEndTime(LocalTime.of(23, 59));
        return schedule;
    }

    @Test
    void booksWithFirstAvailableDoctorAndRoomWhenNoContention() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null);

        assertThat(result.getDoctor()).isEqualTo(drA);
        assertThat(result.getRoom()).isEqualTo(room1);
    }

    @Test
    void retriesNextPairWhenFirstAttemptLosesRaceOnUniqueConstraint() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA, drB));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(fullDaySchedule(drA), fullDaySchedule(drB)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1, room2));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null);

        assertThat(result).isNotNull();
    }

    @Test
    void retriesNextPairWhenRoomReservationFails() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA, drB));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(fullDaySchedule(drA), fullDaySchedule(drB)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1, room2));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RoomReservationFailedException("room rejected", new RuntimeException()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null);

        assertThat(result).isNotNull();
    }

    @Test
    void throwsAllocationExceptionWhenRoomReservationFailsForAllCandidates() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RoomReservationFailedException("room rejected", new RuntimeException()));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void throwsAllocationExceptionWhenAllCandidatePairsExhausted() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void throwsAllocationExceptionWhenRoomAvailabilityCheckFails() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        RoomAvailabilityCheckFailedException cause =
                new RoomAvailabilityCheckFailedException("external system down", new RuntimeException());
        when(roomAvailabilityService.availableRoomsOn(any())).thenThrow(cause);

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null))
                .isInstanceOf(AppointmentAllocationException.class)
                .hasCause(cause);
    }

    @Test
    void stopsRetryingAfterThreeConsecutiveRoomReservationFailures() {
        Room room3 = new Room();
        room3.setId(22L);
        room3.setActive(true);
        Room room4 = new Room();
        room4.setId(23L);
        room4.setActive(true);
        Room room5 = new Room();
        room5.setId(24L);
        room5.setActive(true);
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(roomAvailabilityService.availableRoomsOn(any()))
                .thenReturn(List.of(room1, room2, room3, room4, room5));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RoomReservationFailedException("room rejected", new RuntimeException()));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null))
                .isInstanceOf(AppointmentAllocationException.class);

        verify(bookingAttemptExecutor, times(3)).attemptBook(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doctorExcludedWhenNoScheduleForThatDayOfWeek() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void doctorExcludedWhenRequestedRangeExtendsPastScheduleEndTime() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        DoctorSchedule tooNarrow = fullDaySchedule(drA);
        tooNarrow.setStartTime(LocalTime.of(9, 0));
        tooNarrow.setEndTime(startsAt.toLocalTime().plusMinutes(15));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(tooNarrow));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, 30))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void defaultsToThirtyMinuteDurationWhenNotSpecified() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of(fullDaySchedule(drA)));
        when(roomAvailabilityService.availableRoomsOn(any())).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    appointment.setStartsAt(inv.getArgument(4));
                    appointment.setEndsAt(inv.getArgument(5));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, null);

        assertThat(Duration.between(result.getStartsAt(), result.getEndsAt())).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void throwsSlotValidationExceptionWhenSpecialtyUnknown() {
        when(specialtyRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.book("UNKNOWN", "PAT-0001", startsAt, null))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsSlotValidationExceptionWhenSlotInThePast() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.book(
                "CARDIOLOGY", "PAT-0001", OffsetDateTime.now().minusSeconds(3600), null))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsSlotValidationExceptionWhenDurationNotMultipleOfFifteen() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, 20))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsSlotValidationExceptionWhenDurationExceedsEightHours() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt, 495))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsPatientNotFoundExceptionWhenPatientIdUnknown() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "UNKNOWN", startsAt, null))
                .isInstanceOf(PatientNotFoundException.class);
    }

    // --- bookOnDay (Stage 2: day-only search) ---

    private LocalDate futureDate() {
        return LocalDate.now().plusDays(7);
    }

    private DoctorSchedule scheduleFor(Doctor doctor, LocalDate date, LocalTime start, LocalTime end) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctor(doctor);
        schedule.setDayOfWeek(date.getDayOfWeek());
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        return schedule;
    }

    private Appointment busyAppointment(Doctor doctor, Room room, OffsetDateTime start, OffsetDateTime end) {
        Appointment appointment = new Appointment();
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        appointment.setStartsAt(start);
        appointment.setEndsAt(end);
        return appointment;
    }

    @Test
    void bookOnDayFindsFirstFitWhenDoctorAndRoomFreeAllDay() {
        LocalDate date = futureDate();
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(scheduleFor(drA, date, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        when(roomAvailabilityService.availableRoomsOn(date)).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedAppointmentsForDoctorsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedAppointmentsForRoomsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    appointment.setStartsAt(inv.getArgument(4));
                    appointment.setEndsAt(inv.getArgument(5));
                    return appointment;
                });

        Appointment result = bookingService.bookOnDay("CARDIOLOGY", "PAT-0001", date, ZoneOffset.UTC, 30);

        assertThat(result.getStartsAt().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(result.getEndsAt().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void bookOnDaySkipsDoctorsBusyPeriodAndFindsNextGap() {
        LocalDate date = futureDate();
        OffsetDateTime nineAm = OffsetDateTime.of(date, LocalTime.of(9, 0), ZoneOffset.UTC);
        OffsetDateTime noon = OffsetDateTime.of(date, LocalTime.of(12, 0), ZoneOffset.UTC);
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(scheduleFor(drA, date, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        when(roomAvailabilityService.availableRoomsOn(date)).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedAppointmentsForDoctorsOverlapping(any(), any(), any()))
                .thenReturn(List.of(busyAppointment(drA, room2, nineAm, noon)));
        when(appointmentRepository.findBookedAppointmentsForRoomsOverlapping(any(), any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    appointment.setStartsAt(inv.getArgument(4));
                    appointment.setEndsAt(inv.getArgument(5));
                    return appointment;
                });

        Appointment result = bookingService.bookOnDay("CARDIOLOGY", "PAT-0001", date, ZoneOffset.UTC, 30);

        assertThat(result.getStartsAt().toLocalTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void bookOnDayExcludesDoctorWhoseScheduleIsTooNarrowForDuration() {
        LocalDate date = futureDate();
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(scheduleFor(drA, date, LocalTime.of(9, 0), LocalTime.of(9, 15))));
        when(roomAvailabilityService.availableRoomsOn(date)).thenReturn(List.of(room1));

        assertThatThrownBy(() -> bookingService.bookOnDay("CARDIOLOGY", "PAT-0001", date, ZoneOffset.UTC, 30))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void bookOnDayExcludesDoctorWithNoScheduleForThatDay() {
        LocalDate date = futureDate();
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any())).thenReturn(List.of());
        when(roomAvailabilityService.availableRoomsOn(date)).thenReturn(List.of(room1));

        assertThatThrownBy(() -> bookingService.bookOnDay("CARDIOLOGY", "PAT-0001", date, ZoneOffset.UTC, 30))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void bookOnDayThrowsAllocationExceptionWhenFullyBookedWithinBusinessHours() {
        LocalDate date = futureDate();
        OffsetDateTime nineAm = OffsetDateTime.of(date, LocalTime.of(9, 0), ZoneOffset.UTC);
        OffsetDateTime sixPm = OffsetDateTime.of(date, LocalTime.of(18, 0), ZoneOffset.UTC);
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(doctorScheduleRepository.findByDoctorIdInAndDayOfWeek(any(), any()))
                .thenReturn(List.of(scheduleFor(drA, date, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        when(roomAvailabilityService.availableRoomsOn(date)).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedAppointmentsForDoctorsOverlapping(any(), any(), any()))
                .thenReturn(List.of(busyAppointment(drA, room1, nineAm, sixPm)));
        when(appointmentRepository.findBookedAppointmentsForRoomsOverlapping(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.bookOnDay("CARDIOLOGY", "PAT-0001", date, ZoneOffset.UTC, 30))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void bookOnDayThrowsSlotValidationExceptionWhenDateInThePast() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.bookOnDay(
                "CARDIOLOGY", "PAT-0001", LocalDate.now().minusDays(1), ZoneOffset.UTC, 30))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void bookOnDayThrowsSlotValidationExceptionWhenDurationNotMultipleOfFifteen() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.bookOnDay(
                "CARDIOLOGY", "PAT-0001", futureDate(), ZoneOffset.UTC, 40))
                .isInstanceOf(SlotValidationException.class);
    }
}
