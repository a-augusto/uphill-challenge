package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.uphill.appointments.control.events.AppointmentCancelledEvent;
import com.uphill.appointments.control.exceptions.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.exceptions.AppointmentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.enums.AppointmentStatus;
import com.uphill.appointments.entity.repository.AppointmentRepository;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CancellationService cancellationService;

    private UUID appointmentId;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        cancellationService = new CancellationService(appointmentRepository, eventPublisher);
        appointmentId = UUID.randomUUID();
        appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setStatus(AppointmentStatus.BOOKED);
    }

    @Test
    void cancelsBookedAppointmentAndPublishesEvent() {
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment result = cancellationService.cancel(appointmentId);

        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.getCancelledAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(AppointmentCancelledEvent.class));
    }

    @Test
    void throwsAppointmentNotFoundExceptionWhenAppointmentUnknown() {
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cancellationService.cancel(appointmentId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void throwsAlreadyCancelledExceptionWhenAppointmentAlreadyCancelled() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> cancellationService.cancel(appointmentId))
                .isInstanceOf(AppointmentAlreadyCancelledException.class);
    }
}
