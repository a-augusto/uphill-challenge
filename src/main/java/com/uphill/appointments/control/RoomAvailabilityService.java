package com.uphill.appointments.control;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 *
 * <p>The external call is cached per day for a short TTL — concurrent
 * booking requests for the same day would otherwise each make an identical
 * external call. A plain in-memory map, not a caching library: single
 * method, single instance, doesn't warrant a new dependency. Only
 * successful results are cached, so a failure doesn't get masked for the
 * TTL window — the next call retries immediately.
 */
@Service
@RequiredArgsConstructor
public class RoomAvailabilityService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final RoomRepository roomRepository;
    private final RoomReservationClient roomReservationClient;
    private final ConcurrentMap<LocalDate, CachedEntry> cache = new ConcurrentHashMap<>();

    private record CachedEntry(List<Long> roomIds, Instant fetchedAt) {
    }

    public List<Room> availableRoomsOn(LocalDate date) {
        Set<Long> externallyAvailable;
        try {
            externallyAvailable = new HashSet<>(findAvailableRoomIdsCached(date));
        } catch (Exception e) {
            throw new RoomAvailabilityCheckFailedException(
                    "Unable to determine room availability for " + date, e);
        }
        return roomRepository.findByActiveTrue().stream()
                .filter(room -> externallyAvailable.contains(room.getId()))
                .toList();
    }

    private List<Long> findAvailableRoomIdsCached(LocalDate date) {
        CachedEntry cached = cache.get(date);
        if (cached != null && Instant.now().isBefore(cached.fetchedAt().plus(CACHE_TTL))) {
            return cached.roomIds();
        }
        List<Long> roomIds = roomReservationClient.findAvailableRoomIds(date);
        cache.put(date, new CachedEntry(roomIds, Instant.now()));
        return roomIds;
    }
}
