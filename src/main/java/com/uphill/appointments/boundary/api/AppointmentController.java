package com.uphill.appointments.boundary.api;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;
import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.boundary.api.idempotency.IdempotencyKeyStore;
import com.uphill.appointments.control.BookingService;
import com.uphill.appointments.control.CancellationService;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.AppointmentSpecifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AppointmentController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;
    private final AppointmentRepository appointmentRepository;
    private final IdempotencyKeyStore idempotencyKeyStore;

    @Operation(summary = "Book an appointment, auto-assigning an available doctor, room, and (if startTime is "
            + "omitted) a time within extended business hours. An optional Idempotency-Key header replays the "
            + "same response for a repeated key instead of booking again.")
    @PostMapping("/api/appointments")
    public ResponseEntity<AppointmentResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Parameter(example = "5b1b3f3a-6e0a-4b8a-9c1e-2a7f6b0d1c3e",
                    description = "Optional — replays the original response for a repeated key instead of "
                            + "booking again")
            String idempotencyKey,
            @Valid @RequestBody CreateAppointmentRequest request) {
        if (idempotencyKey != null) {
            Optional<IdempotencyKeyStore.StoredResponse> cached = idempotencyKeyStore.get(idempotencyKey);
            if (cached.isPresent()) {
                return ResponseEntity.status(cached.get().status()).body(cached.get().body());
            }
        }

        Appointment appointment;
        if (request.startTime() != null) {
            OffsetDateTime startsAt = OffsetDateTime.of(request.date(), request.startTime(), request.offset());
            appointment = bookingService.book(
                    request.specialtyCode(), request.patientId(), startsAt, request.durationMinutes());
        } else {
            appointment = bookingService.bookOnDay(
                    request.specialtyCode(), request.patientId(), request.date(), request.offset(),
                    request.durationMinutes());
        }

        AppointmentResponse body = AppointmentResponse.from(appointment);
        if (idempotencyKey != null) {
            idempotencyKeyStore.put(idempotencyKey, HttpStatus.CREATED, body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "Cancel an appointment, freeing its doctor/room/slot for rebooking. patientId must match "
            + "the patient the appointment was booked under — there is no login, so this is the only thing "
            + "stopping someone who merely learned the appointment id from cancelling someone else's booking.")
    @PostMapping("/api/appointments/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(
            @PathVariable
            @Parameter(example = "79051f77-13c7-454a-9760-dbacadb66efb")
            UUID id,
            @RequestParam
            @Parameter(example = "PAT-000001", description = "Must match the patientId the appointment was "
                    + "booked under, or this returns 404 the same as an unknown id")
            String patientId) {
        Appointment appointment = cancellationService.cancel(id, patientId);
        return ResponseEntity.ok(AppointmentResponse.from(appointment));
    }

    @Operation(summary = "List scheduled appointments (admin)")
    @Parameters({
            @Parameter(name = "page", example = "0", description = "Zero-based page index"),
            @Parameter(name = "size", example = "20", description = "Page size, capped at 100 (see application.properties)"),
            @Parameter(name = "sort", example = "startsAt,desc", description = "Optional — property,(asc|desc), repeatable")
    })
    @GetMapping("/api/appointments")
    public Page<AppointmentResponse> list(
            @RequestParam(required = false)
            @Parameter(example = "CARDIOLOGY",
                    description = "Optional — one of the seeded specialty codes. Leave blank/omit to list every "
                            + "specialty (no filter is applied)")
            String specialty,
            @RequestParam(required = false)
            @Parameter(example = "2026-08-26T00:00:00Z", description = "Optional — inclusive lower bound on startsAt")
            OffsetDateTime from,
            @RequestParam(required = false)
            @Parameter(example = "2026-08-27T00:00:00Z", description = "Optional — inclusive upper bound on startsAt")
            OffsetDateTime to,
            @Parameter(hidden = true) Pageable pageable) {
        Specification<Appointment> spec = Specification.allOf(Stream.of(
                        AppointmentSpecifications.hasSpecialtyCode(specialty),
                        AppointmentSpecifications.startsAtFrom(from),
                        AppointmentSpecifications.startsAtTo(to),
                        AppointmentSpecifications.fetchAssociationsForListing())
                .filter(Objects::nonNull)
                .toList());
        return appointmentRepository.findAll(spec, pageable).map(AppointmentResponse::from);
    }
}
