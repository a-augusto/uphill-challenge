package com.uphill.appointments.entity.repository;

import java.time.DayOfWeek;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.entity.DoctorSchedule;

public interface DoctorScheduleRepository extends JpaRepository<DoctorSchedule, Long> {

    List<DoctorSchedule> findByDoctorIdInAndDayOfWeek(List<Long> doctorIds, DayOfWeek dayOfWeek);
}
