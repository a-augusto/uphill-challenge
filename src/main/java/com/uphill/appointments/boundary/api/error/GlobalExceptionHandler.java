package com.uphill.appointments.boundary.api.error;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import com.uphill.appointments.boundary.api.dto.ErrorResponse;
import com.uphill.appointments.control.AppointmentAllocationException;
import com.uphill.appointments.control.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.AppointmentNotFoundException;
import com.uphill.appointments.control.PatientNotFoundException;
import com.uphill.appointments.control.RoomAvailabilityCheckFailedException;
import com.uphill.appointments.control.SlotValidationException;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, request, ex);
    }

    @ExceptionHandler(SlotValidationException.class)
    public ResponseEntity<ErrorResponse> handleSlotValidation(SlotValidationException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(AppointmentAllocationException.class)
    public ResponseEntity<ErrorResponse> handleAllocation(AppointmentAllocationException ex, WebRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePatientNotFound(PatientNotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAppointmentNotFound(AppointmentNotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(AppointmentAlreadyCancelledException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyCancelled(
            AppointmentAlreadyCancelledException ex, WebRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(RoomAvailabilityCheckFailedException.class)
    public ResponseEntity<ErrorResponse> handleRoomAvailabilityCheckFailed(
            RoomAvailabilityCheckFailedException ex, WebRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, ex);
    }

    /**
     * Every error response - and its log line - funnels through here, so the
     * log level always matches the status: client errors are routine (INFO),
     * an external system misbehaving is an operational signal (WARN), and
     * anything else genuinely unexpected (ERROR). Logging every outcome at
     * WARN/ERROR would make those levels useless for alerting - most 4xx
     * responses here are normal business outcomes, not problems.
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, WebRequest request, Exception ex) {
        String path = request.getDescription(false).replace("uri=", "");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            log.warn("{} -> {} {}: {}", path, status.value(), status.getReasonPhrase(), message, ex);
        } else if (status.is5xxServerError()) {
            log.error("{} -> {} {}: {}", path, status.value(), status.getReasonPhrase(), message, ex);
        } else {
            log.info("{} -> {} {}: {}", path, status.value(), status.getReasonPhrase(), message);
        }
        ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(body);
    }
}
