package com.uphill.appointments.boundary.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.uphill.appointments.control.RoomAvailabilityCheckFailedException;
import com.uphill.appointments.control.RoomAvailabilityService;
import com.uphill.appointments.entity.Room;

@WebMvcTest(RoomController.class)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RoomAvailabilityService roomAvailabilityService;

    @Test
    void availabilityReturns200WithExternallyAvailableRooms() throws Exception {
        Room room = new Room();
        room.setId(1L);
        room.setName("Room 1");
        when(roomAvailabilityService.availableRoomsOn(any(LocalDate.class))).thenReturn(List.of(room));

        mockMvc.perform(get("/api/rooms/availability?date=2026-08-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Room 1"));
    }

    @Test
    void availabilityReturns503WhenExternalCheckFails() throws Exception {
        when(roomAvailabilityService.availableRoomsOn(any(LocalDate.class)))
                .thenThrow(new RoomAvailabilityCheckFailedException("external system down", new RuntimeException()));

        mockMvc.perform(get("/api/rooms/availability?date=2026-08-20"))
                .andExpect(status().isServiceUnavailable());
    }
}
