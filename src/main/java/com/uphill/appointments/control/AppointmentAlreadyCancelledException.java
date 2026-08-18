package com.uphill.appointments.control;

public class AppointmentAlreadyCancelledException extends RuntimeException {

    public AppointmentAlreadyCancelledException(String message) {
        super(message);
    }
}
