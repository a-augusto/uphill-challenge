package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.PatientInfo;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.control.AppointmentBookedEvent;
import com.uphill.appointments.entity.repository.AppointmentRepository;

@ExtendWith(MockitoExtension.class)
class BookingAttemptExecutorTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BookingAttemptExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BookingAttemptExecutor(appointmentRepository, eventPublisher);
    }

    @Test
    void publishesBookedEventAfterPersistingTheAppointment() {
        Specialty specialty = new Specialty(1L, "CARDIOLOGY", "Cardiology");
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        Room room = new Room();
        room.setId(1L);
        PatientInfo patient = new PatientInfo("Jane Doe", "jane@example.com", null);
        Instant startsAt = Instant.now().plus(Duration.ofDays(1));
        Instant endsAt = startsAt.plus(Duration.ofMinutes(30));

        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment result = executor.attemptBook(doctor, room, specialty, patient, startsAt, endsAt);

        assertThat(result.getDoctor()).isEqualTo(doctor);
        verify(eventPublisher).publishEvent(any(AppointmentBookedEvent.class));
    }
}
