package com.uphill.appointments.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.uphill.appointments.entity.enums.Gender;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "patient")
@Getter
@Setter
@NoArgsConstructor
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id")
    private String patientId;

    private String name;

    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String address;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    @ElementCollection
    @CollectionTable(name = "patient_language", joinColumns = @JoinColumn(name = "patient_id"))
    @Column(name = "language")
    private List<String> languagesSpoken = new ArrayList<>();
}
