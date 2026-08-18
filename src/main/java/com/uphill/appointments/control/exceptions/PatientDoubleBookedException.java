package com.uphill.appointments.control.exceptions;

public class PatientDoubleBookedException extends RuntimeException {

    public PatientDoubleBookedException(String message) {
        super(message);
    }
}
