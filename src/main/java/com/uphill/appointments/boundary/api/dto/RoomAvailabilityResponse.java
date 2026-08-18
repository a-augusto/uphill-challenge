package com.uphill.appointments.boundary.api.dto;

import com.uphill.appointments.entity.Room;

public record RoomAvailabilityResponse(Long id, String name) {

    public static RoomAvailabilityResponse from(Room room) {
        return new RoomAvailabilityResponse(room.getId(), room.getName());
    }
}
