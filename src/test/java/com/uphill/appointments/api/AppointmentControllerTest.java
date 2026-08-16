package com.uphill.appointments.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
import com.uphill.appointments.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.booking.AppointmentAllocationException;
import com.uphill.appointments.booking.BookingService;
import com.uphill.appointments.booking.SlotValidationException;
import com.uphill.appointments.domain.Appointment;
import com.uphill.appointments.domain.Doctor;
import com.uphill.appointments.domain.PatientInfo;
import com.uphill.appointments.domain.Room;
import com.uphill.appointments.domain.Specialty;
import com.uphill.appointments.repository.AppointmentRepository;

@WebMvcTest(AppointmentController.class)
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private AppointmentRepository appointmentRepository;

    @Test
    void createReturns201WithBookedAppointmentDetails() throws Exception {
        Appointment appointment = sampleAppointment();
        when(bookingService.book(anyString(), any(PatientInfo.class), any(Instant.class))).thenReturn(appointment);

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorName").value("Dr. Ana Ferreira"))
                .andExpect(jsonPath("$.roomName").value("Room 1"))
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    void createReturns400WhenPatientNameMissing() throws Exception {
        String invalidBody = """
                {"patientEmail":"jane@example.com","specialtyCode":"CARDIOLOGY","startsAt":"%s"}
                """.formatted(futureSlot());

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("patientName")));
    }

    @Test
    void createReturns409WhenNoDoctorOrRoomAvailable() throws Exception {
        when(bookingService.book(anyString(), any(PatientInfo.class), any(Instant.class)))
                .thenThrow(new AppointmentAllocationException("No available doctor/room"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void createReturns400WhenSpecialtyUnknown() throws Exception {
        when(bookingService.book(anyString(), any(PatientInfo.class), any(Instant.class)))
                .thenThrow(new SlotValidationException("Unknown specialty code: NOPE"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isBadRequest());
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
        return new CreateAppointmentRequest("Jane Doe", "jane@example.com", "912345678", "CARDIOLOGY", futureSlot());
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

        Appointment appointment = new Appointment();
        appointment.setPatient(new PatientInfo("Jane Doe", "jane@example.com", "912345678"));
        appointment.setSpecialty(specialty);
        appointment.setDoctor(doctor);
        appointment.setRoom(room);
        Instant startsAt = futureSlot();
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        appointment.setId(java.util.UUID.randomUUID());
        appointment.setCreatedAt(Instant.now());
        return appointment;
    }

    private static Instant futureSlot() {
        return Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }
}
