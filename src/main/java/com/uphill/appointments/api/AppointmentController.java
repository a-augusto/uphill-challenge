package com.uphill.appointments.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uphill.appointments.api.dto.AppointmentResponse;
import com.uphill.appointments.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.booking.BookingService;
import com.uphill.appointments.domain.Appointment;
import com.uphill.appointments.domain.PatientInfo;
import com.uphill.appointments.repository.AppointmentRepository;
import com.uphill.appointments.repository.AppointmentSpecifications;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AppointmentController {

    private final BookingService bookingService;
    private final AppointmentRepository appointmentRepository;

    @Operation(summary = "Book an appointment, auto-assigning an available doctor and room")
    @PostMapping("/api/appointments")
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request) {
        PatientInfo patient = new PatientInfo(request.patientName(), request.patientEmail(), request.patientPhone());
        Appointment appointment = bookingService.book(request.specialtyCode(), patient, request.startsAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(appointment));
    }

    @Operation(summary = "List scheduled appointments (admin)")
    @GetMapping("/api/appointments")
    public Page<AppointmentResponse> list(
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable) {
        Specification<Appointment> spec = Specification.allOf(Stream.of(
                        AppointmentSpecifications.hasSpecialtyCode(specialty),
                        AppointmentSpecifications.startsAtFrom(from),
                        AppointmentSpecifications.startsAtTo(to))
                .filter(Objects::nonNull)
                .toList());
        return appointmentRepository.findAll(spec, pageable).map(AppointmentResponse::from);
    }
}
