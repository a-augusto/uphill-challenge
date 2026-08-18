package com.uphill.appointments.boundary.api;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uphill.appointments.boundary.api.dto.RoomAvailabilityResponse;
import com.uphill.appointments.control.RoomAvailabilityService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RoomController {

    private final RoomAvailabilityService roomAvailabilityService;

    @Operation(summary = "Preview which rooms the external system reports as available on a given day")
    @GetMapping("/api/rooms/availability")
    public List<RoomAvailabilityResponse> availability(@RequestParam OffsetDateTime date) {
        return roomAvailabilityService.availableRoomsOn(date).stream()
                .map(RoomAvailabilityResponse::from)
                .toList();
    }
}
