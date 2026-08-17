package com.uphill.appointments.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PatientInfo {

    @Column(name = "patient_name")
    private String name;

    @Column(name = "patient_email")
    private String email;

    @Column(name = "patient_phone")
    private String phone;
}
