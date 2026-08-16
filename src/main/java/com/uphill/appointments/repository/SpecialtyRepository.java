package com.uphill.appointments.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.domain.Specialty;

public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {

    Optional<Specialty> findByCode(String code);
}
