package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.RoomRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private SpecialtyRepository specialtyRepository;
    @Mock
    private DoctorRepository doctorRepository;
    @Mock
    private RoomRepository roomRepository;
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
    private Instant startsAt;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                specialtyRepository, doctorRepository, roomRepository, patientRepository,
                appointmentRepository, bookingAttemptExecutor);

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

        startsAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    }

    @Test
    void booksWithFirstAvailableDoctorAndRoomWhenNoContention() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsAtSlot(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsAtSlot(any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt);

        assertThat(result.getDoctor()).isEqualTo(drA);
        assertThat(result.getRoom()).isEqualTo(room1);
    }

    @Test
    void retriesNextPairWhenFirstAttemptLosesRaceOnUniqueConstraint() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA, drB));
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1, room2));
        when(appointmentRepository.findBookedDoctorIdsAtSlot(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsAtSlot(any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt);

        assertThat(result).isNotNull();
    }

    @Test
    void retriesNextPairWhenRoomReservationFails() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA, drB));
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1, room2));
        when(appointmentRepository.findBookedDoctorIdsAtSlot(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsAtSlot(any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RoomReservationFailedException("room rejected", new RuntimeException()))
                .thenAnswer(inv -> {
                    Appointment appointment = new Appointment();
                    appointment.setDoctor(inv.getArgument(0));
                    appointment.setRoom(inv.getArgument(1));
                    return appointment;
                });

        Appointment result = bookingService.book("CARDIOLOGY", "PAT-0001", startsAt);

        assertThat(result).isNotNull();
    }

    @Test
    void throwsAllocationExceptionWhenRoomReservationFailsForAllCandidates() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsAtSlot(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsAtSlot(any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RoomReservationFailedException("room rejected", new RuntimeException()));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void throwsAllocationExceptionWhenAllCandidatePairsExhausted() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));
        when(doctorRepository.findBySpecialtyAndActiveTrue(cardiology)).thenReturn(List.of(drA));
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1));
        when(appointmentRepository.findBookedDoctorIdsAtSlot(any(), any())).thenReturn(List.of());
        when(appointmentRepository.findBookedRoomIdsAtSlot(any(), any())).thenReturn(List.of());
        when(bookingAttemptExecutor.attemptBook(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", startsAt))
                .isInstanceOf(AppointmentAllocationException.class);
    }

    @Test
    void throwsSlotValidationExceptionWhenSpecialtyUnknown() {
        when(specialtyRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.book("UNKNOWN", "PAT-0001", startsAt))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsSlotValidationExceptionWhenSlotInThePast() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("PAT-0001")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "PAT-0001", Instant.now().minusSeconds(3600)))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void throwsPatientNotFoundExceptionWhenPatientIdUnknown() {
        when(specialtyRepository.findByCode("CARDIOLOGY")).thenReturn(Optional.of(cardiology));
        when(patientRepository.findByPatientId("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.book("CARDIOLOGY", "UNKNOWN", startsAt))
                .isInstanceOf(PatientNotFoundException.class);
    }
}
