package com.uphill.appointments.entity.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uphill.appointments.entity.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    @Query("""
            select a.doctor.id from Appointment a where a.startsAt = :startsAt and a.doctor.id in :doctorIds
            """)
    List<Long> findBookedDoctorIdsAtSlot(@Param("startsAt") Instant startsAt, @Param("doctorIds") List<Long> doctorIds);

    @Query("""
            select a.room.id from Appointment a where a.startsAt = :startsAt and a.room.id in :roomIds
            """)
    List<Long> findBookedRoomIdsAtSlot(@Param("startsAt") Instant startsAt, @Param("roomIds") List<Long> roomIds);
}
