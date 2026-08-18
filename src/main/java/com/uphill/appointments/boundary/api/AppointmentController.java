package com.uphill.appointments.boundary.api;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;
import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.control.BookingService;
import com.uphill.appointments.control.CancellationService;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.AppointmentSpecifications;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AppointmentController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;
    private final AppointmentRepository appointmentRepository;

    @Operation(summary = "Book an appointment, auto-assigning an available doctor and room")
    @PostMapping("/api/appointments")
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request) {
        Appointment appointment = bookingService.book(request.specialtyCode(), request.patientId(), request.startsAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(appointment));
    }

    @Operation(summary = "Cancel an appointment, freeing its doctor/room/slot for rebooking")
    @PostMapping("/api/appointments/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable UUID id) {
        Appointment appointment = cancellationService.cancel(id);
        return ResponseEntity.ok(AppointmentResponse.from(appointment));
    }

    @Operation(summary = "List scheduled appointments (admin)")
    @GetMapping("/api/appointments")
    public Page<AppointmentResponse> list(
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
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
