package com.uphill.appointments.control;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.uphill.appointments.boundary.external.RoomReservationClient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.repository.RoomRepository;

import lombok.RequiredArgsConstructor;

/**
 * Asks the external room-reservation system which of our active rooms it
 * considers available on a given day, and intersects that with our own
 * active-room set. The external system is the source of truth here — our DB
 * only knows about rooms it has itself booked, not holds placed by other
 * systems sharing the same physical rooms (maintenance, other departments).
 * Shared by {@link BookingService} (which layers its own exact-slot check on
 * top) and the room-availability preview endpoint.
 */
@Service
@RequiredArgsConstructor
public class RoomAvailabilityService {

    private final RoomRepository roomRepository;
    private final RoomReservationClient roomReservationClient;

    public List<Room> availableRoomsOn(LocalDate date) {
        Set<Long> externallyAvailable;
        try {
            externallyAvailable = new HashSet<>(roomReservationClient.findAvailableRoomIds(date));
        } catch (Exception e) {
            throw new RoomAvailabilityCheckFailedException(
                    "Unable to determine room availability for " + date, e);
        }
        return roomRepository.findByActiveTrue().stream()
                .filter(room -> externallyAvailable.contains(room.getId()))
                .toList();
    }
}
