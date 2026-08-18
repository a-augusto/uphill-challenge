package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class RoomAvailabilityServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomReservationClient roomReservationClient;

    private RoomAvailabilityService roomAvailabilityService;

    @Test
    void returnsOnlyRoomsTheExternalSystemReportsAvailable() {
        roomAvailabilityService = new RoomAvailabilityService(roomRepository, roomReservationClient);
        Room room1 = new Room();
        room1.setId(1L);
        Room room2 = new Room();
        room2.setId(2L);
        LocalDate date = LocalDate.now().plusDays(1);
        when(roomRepository.findByActiveTrue()).thenReturn(List.of(room1, room2));
        when(roomReservationClient.findAvailableRoomIds(date)).thenReturn(List.of(1L));

        List<Room> result = roomAvailabilityService.availableRoomsOn(date);

        assertThat(result).containsExactly(room1);
    }

    @Test
    void wrapsExternalClientFailureInRoomAvailabilityCheckFailedException() {
        roomAvailabilityService = new RoomAvailabilityService(roomRepository, roomReservationClient);
        LocalDate date = LocalDate.now().plusDays(1);
        when(roomReservationClient.findAvailableRoomIds(date)).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> roomAvailabilityService.availableRoomsOn(date))
                .isInstanceOf(RoomAvailabilityCheckFailedException.class);
    }
}
