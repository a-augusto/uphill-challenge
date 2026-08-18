package com.uphill.appointments.boundary.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.uphill.appointments.config.SecurityConfig;
import com.uphill.appointments.control.exceptions.AppointmentAllocationException;
import com.uphill.appointments.control.exceptions.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.BookingService;
import com.uphill.appointments.control.CancellationService;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

@WebMvcTest(AdminAppointmentsController.class)
@Import({SecurityConfig.class, SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
class AdminAppointmentsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private CancellationService cancellationService;
    @MockitoBean
    private AppointmentRepository appointmentRepository;
    @MockitoBean
    private SpecialtyRepository specialtyRepository;

    @Test
    void listRendersAppointmentsPage() throws Exception {
        when(appointmentRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleAppointment())));
        when(specialtyRepository.findAll()).thenReturn(List.of(new Specialty(1L, "CARDIOLOGY", "Cardiology")));

        mockMvc.perform(get("/admin/appointments").with(httpBasic("admin", "admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dr. Ana Ferreira")))
                .andExpect(content().string(containsString("Room 1")));
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin/appointments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bookRedirectsWithSuccessMessage() throws Exception {
        Appointment appointment = sampleAppointment();
        when(bookingService.book(anyString(), anyString(), any(), any())).thenReturn(appointment);

        mockMvc.perform(post("/admin/appointments")
                        .with(httpBasic("admin", "admin"))
                        .with(csrf())
                        .param("patientId", "PAT-0001")
                        .param("specialtyCode", "CARDIOLOGY")
                        .param("date", "2026-08-25")
                        .param("startTime", "09:00")
                        .param("endTime", "09:30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/appointments"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    void bookRedirectsWithErrorMessageWhenEndTimeNotAfterStartTime() throws Exception {
        mockMvc.perform(post("/admin/appointments")
                        .with(httpBasic("admin", "admin"))
                        .with(csrf())
                        .param("patientId", "PAT-0001")
                        .param("specialtyCode", "CARDIOLOGY")
                        .param("date", "2026-08-25")
                        .param("startTime", "09:30")
                        .param("endTime", "09:00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void bookRedirectsWithErrorMessageWhenAllocationFails() throws Exception {
        when(bookingService.book(anyString(), anyString(), any(), any()))
                .thenThrow(new AppointmentAllocationException("No available doctor/room"));

        mockMvc.perform(post("/admin/appointments")
                        .with(httpBasic("admin", "admin"))
                        .with(csrf())
                        .param("patientId", "PAT-0001")
                        .param("specialtyCode", "CARDIOLOGY")
                        .param("date", "2026-08-25")
                        .param("startTime", "09:00")
                        .param("endTime", "09:30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void cancelRedirectsWithSuccessMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(cancellationService.cancel(id)).thenReturn(sampleAppointment());

        mockMvc.perform(post("/admin/appointments/{id}/cancel", id)
                        .with(httpBasic("admin", "admin"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    void cancelRedirectsWithErrorMessageWhenAlreadyCancelled() throws Exception {
        UUID id = UUID.randomUUID();
        when(cancellationService.cancel(id))
                .thenThrow(new AppointmentAlreadyCancelledException("Appointment already cancelled: " + id));

        mockMvc.perform(post("/admin/appointments/{id}/cancel", id)
                        .with(httpBasic("admin", "admin"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
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
        OffsetDateTime startsAt = OffsetDateTime.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(startsAt.plus(Duration.ofMinutes(30)));
        appointment.setId(UUID.randomUUID());
        return appointment;
    }
}
