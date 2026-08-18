package com.uphill.appointments.control.exceptions;

public class AppointmentAllocationException extends RuntimeException {

    public AppointmentAllocationException(String message) {
        super(message);
    }

    public AppointmentAllocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
