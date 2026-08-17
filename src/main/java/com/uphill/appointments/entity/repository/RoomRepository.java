package com.uphill.appointments.entity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uphill.appointments.entity.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByActiveTrue();
}
