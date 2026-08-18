package com.uphill.appointments.control.exceptions;

public class AppointmentAlreadyCancelledException extends RuntimeException {

    public AppointmentAlreadyCancelledException(String message) {
        super(message);
    }
}
