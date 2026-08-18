package com.uphill.appointments.entity.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uphill.appointments.entity.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    @Query("""
            select a.doctor.id from Appointment a
            where a.status = com.uphill.appointments.entity.AppointmentStatus.BOOKED
              and a.doctor.id in :doctorIds
              and a.startsAt < :endsAt and a.endsAt > :startsAt
            """)
    List<Long> findBookedDoctorIdsOverlapping(
            @Param("startsAt") OffsetDateTime startsAt, @Param("endsAt") OffsetDateTime endsAt,
            @Param("doctorIds") List<Long> doctorIds);

    @Query("""
            select a.room.id from Appointment a
            where a.status = com.uphill.appointments.entity.AppointmentStatus.BOOKED
              and a.room.id in :roomIds
              and a.startsAt < :endsAt and a.endsAt > :startsAt
            """)
    List<Long> findBookedRoomIdsOverlapping(
            @Param("startsAt") OffsetDateTime startsAt, @Param("endsAt") OffsetDateTime endsAt,
            @Param("roomIds") List<Long> roomIds);

    @Query("""
            select a from Appointment a
            where a.status = com.uphill.appointments.entity.AppointmentStatus.BOOKED
              and a.doctor.id in :doctorIds
              and a.startsAt < :rangeEnd and a.endsAt > :rangeStart
            """)
    List<Appointment> findBookedAppointmentsForDoctorsOverlapping(
            @Param("doctorIds") List<Long> doctorIds,
            @Param("rangeStart") OffsetDateTime rangeStart, @Param("rangeEnd") OffsetDateTime rangeEnd);

    @Query("""
            select a from Appointment a
            where a.status = com.uphill.appointments.entity.AppointmentStatus.BOOKED
              and a.room.id in :roomIds
              and a.startsAt < :rangeEnd and a.endsAt > :rangeStart
            """)
    List<Appointment> findBookedAppointmentsForRoomsOverlapping(
            @Param("roomIds") List<Long> roomIds,
            @Param("rangeStart") OffsetDateTime rangeStart, @Param("rangeEnd") OffsetDateTime rangeEnd);
}
