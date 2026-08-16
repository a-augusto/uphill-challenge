package com.uphill.appointments.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.domain.Doctor;
import com.uphill.appointments.domain.Specialty;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findBySpecialtyAndActiveTrue(Specialty specialty);
}
