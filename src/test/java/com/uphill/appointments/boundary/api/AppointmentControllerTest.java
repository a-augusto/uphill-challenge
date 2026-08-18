package com.uphill.appointments.boundary.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.control.AppointmentAllocationException;
import com.uphill.appointments.control.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.AppointmentNotFoundException;
import com.uphill.appointments.control.BookingService;
import com.uphill.appointments.control.CancellationService;
import com.uphill.appointments.control.PatientNotFoundException;
import com.uphill.appointments.control.SlotValidationException;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.AppointmentStatus;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;

@WebMvcTest(AppointmentController.class)
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private CancellationService cancellationService;
    @MockitoBean
    private AppointmentRepository appointmentRepository;

    @Test
    void createReturns201WithBookedAppointmentDetails() throws Exception {
        Appointment appointment = sampleAppointment();
        when(bookingService.book(anyString(), anyString(), any(OffsetDateTime.class), any())).thenReturn(appointment);

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value("PAT-0001"))
                .andExpect(jsonPath("$.doctorName").value("Dr. Ana Ferreira"))
                .andExpect(jsonPath("$.roomName").value("Room 1"))
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    void createReturns400WhenPatientIdMissing() throws Exception {
        String invalidBody = """
                {"specialtyCode":"CARDIOLOGY","date":"%s","startTime":"09:00:00","offset":"+00:00"}
                """.formatted(futureSlot().toLocalDate());

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("patientId")));
    }

    @Test
    void createReturns409WhenNoDoctorOrRoomAvailable() throws Exception {
        when(bookingService.book(anyString(), anyString(), any(OffsetDateTime.class), any()))
                .thenThrow(new AppointmentAllocationException("No available doctor/room"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void createReturns400WhenSpecialtyUnknown() throws Exception {
        when(bookingService.book(anyString(), anyString(), any(OffsetDateTime.class), any()))
                .thenThrow(new SlotValidationException("Unknown specialty code: NOPE"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns404WhenPatientIdUnknown() throws Exception {
        when(bookingService.book(anyString(), anyString(), any(OffsetDateTime.class), any()))
                .thenThrow(new PatientNotFoundException("Unknown patientId: NOPE"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRoutesToBookOnDayWhenStartTimeOmitted() throws Exception {
        Appointment appointment = sampleAppointment();
        LocalDate date = futureSlot().toLocalDate();
        when(bookingService.bookOnDay(anyString(), anyString(), any(LocalDate.class), any(ZoneOffset.class), any()))
                .thenReturn(appointment);
        String body = """
                {"patientId":"PAT-0001","specialtyCode":"CARDIOLOGY","date":"%s","offset":"+00:00"}
                """.formatted(date);

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorName").value("Dr. Ana Ferreira"));
    }

    @Test
    void cancelReturns200WithCancelledAppointmentDetails() throws Exception {
        Appointment appointment = sampleAppointment();
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledAt(OffsetDateTime.now());
        when(cancellationService.cancel(appointment.getId())).thenReturn(appointment);

        mockMvc.perform(post("/api/appointments/{id}/cancel", appointment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelReturns404WhenAppointmentUnknown() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(cancellationService.cancel(unknownId))
                .thenThrow(new AppointmentNotFoundException("Unknown appointment: " + unknownId));

        mockMvc.perform(post("/api/appointments/{id}/cancel", unknownId))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelReturns409WhenAlreadyCancelled() throws Exception {
        UUID id = UUID.randomUUID();
        when(cancellationService.cancel(id))
                .thenThrow(new AppointmentAlreadyCancelledException("Appointment already cancelled: " + id));

        mockMvc.perform(post("/api/appointments/{id}/cancel", id))
                .andExpect(status().isConflict());
    }

    @Test
    void listReturnsPagedAppointments() throws Exception {
        when(appointmentRepository.findAll(ArgumentMatchers.<Specification<Appointment>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleAppointment())));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].doctorName").value("Dr. Ana Ferreira"));
    }

    private static CreateAppointmentRequest sampleRequest() {
        OffsetDateTime startsAt = futureSlot();
        return new CreateAppointmentRequest(
                "PAT-0001", "CARDIOLOGY", startsAt.toLocalDate(), startsAt.toLocalTime(), startsAt.getOffset(), null);
    }

    private static Appointment sampleAppointment() {
        Specialty specialty = new Specialty(1L, "CARDIOLOGY", "Cardiology");
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        doctor.setName("Dr. Ana Ferreira");
        doctor.setSpecialty(specialty);
        doctor.setActive(true);
        Room room = new Room();
        room.setId(1L);
        room.setName("Room 1");
        room.setActive(true);
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setPatientId("PAT-0001");
        patient.setName("Jane Doe");
        patient.setEmail("jane@example.com");

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        OffsetDateTime startsAt = futureSlot();
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        appointment.setId(java.util.UUID.randomUUID());
        appointment.setCreatedAt(OffsetDateTime.now());
        return appointment;
    }

    private static OffsetDateTime futureSlot() {
        return OffsetDateTime.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }
}
