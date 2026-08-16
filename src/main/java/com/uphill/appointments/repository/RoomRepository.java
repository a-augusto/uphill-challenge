package com.uphill.appointments.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByActiveTrue();
}
