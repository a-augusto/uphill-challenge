package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClientException;

import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;

@ExtendWith(MockitoExtension.class)
class BookingAttemptExecutorTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private RoomReservationClient roomReservationClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BookingAttemptExecutor executor;

    private Specialty specialty;
    private Doctor doctor;
    private Room room;
    private Patient patient;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    @BeforeEach
    void setUp() {
        executor = new BookingAttemptExecutor(appointmentRepository, roomReservationClient, eventPublisher);

        specialty = new Specialty(1L, "CARDIOLOGY", "Cardiology");
        doctor = new Doctor();
        doctor.setId(1L);
        room = new Room();
        room.setId(1L);
        patient = new Patient();
        patient.setId(1L);
        patient.setPatientId("PAT-0001");
        patient.setName("Jane Doe");
        patient.setEmail("jane@example.com");
        startsAt = OffsetDateTime.now().plus(Duration.ofDays(1));
        endsAt = startsAt.plus(Duration.ofMinutes(30));
    }

    @Test
    void reservesRoomAndPublishesBookedEventAfterPersistingTheAppointment() {
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment result = executor.attemptBook(doctor, room, specialty, patient, startsAt, endsAt);

        assertThat(result.getDoctor()).isEqualTo(doctor);
        verify(roomReservationClient).reserveRoom(eq(room.getId()), eq(startsAt), eq(endsAt), any());
        verify(eventPublisher).publishEvent(any(AppointmentBookedEvent.class));
    }

    @Test
    void throwsRoomReservationFailedAndNeverPublishesEventWhenExternalRoomCallFails() {
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RestClientException("room system unavailable"))
                .when(roomReservationClient).reserveRoom(any(), any(), any(), any());

        assertThatThrownBy(() -> executor.attemptBook(doctor, room, specialty, patient, startsAt, endsAt))
                .isInstanceOf(RoomReservationFailedException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
