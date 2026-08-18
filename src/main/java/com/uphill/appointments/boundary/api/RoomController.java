package com.uphill.appointments.boundary.api;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uphill.appointments.boundary.api.dto.RoomAvailabilityResponse;
import com.uphill.appointments.control.RoomAvailabilityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RoomController {

    private final RoomAvailabilityService roomAvailabilityService;

    @Operation(summary = "Preview which rooms the external system reports as available on a given day")
    @GetMapping("/api/rooms/availability")
    public List<RoomAvailabilityResponse> availability(
            @RequestParam
            @Parameter(example = "2026-08-26T00:00:00Z", description = "Only the date component is used — the "
                    + "external system reports availability per day, not per instant")
            OffsetDateTime date) {
        return roomAvailabilityService.availableRoomsOn(date).stream()
                .map(RoomAvailabilityResponse::from)
                .toList();
    }
}
