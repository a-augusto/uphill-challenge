package com.uphill.appointments.entity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.entity.Doctor;
import com.uphill.appointments.entity.Specialty;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findBySpecialtyAndActiveTrue(Specialty specialty);
}
