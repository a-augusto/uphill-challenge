package com.uphill.appointments.boundary.api.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;

/**
 * Lets a client retry {@code POST /api/appointments} after not seeing the
 * first response (dropped connection, timeout) without risking a duplicate
 * booking: repeating the same {@code Idempotency-Key} replays the original
 * response instead of re-running the booking.
 *
 * <p>Deliberately simple, for the scope of what's actually reported (a
 * client retrying because it never saw the response): only successful
 * responses are stored (a failed attempt didn't create anything, so simply
 * retrying it is already safe), a reused key isn't checked against the
 * original request body, and two truly simultaneous requests with the same
 * brand-new key can both execute (no locking/coalescing). In-memory, so
 * this only dedupes within a single instance — noted as a known limitation
 * alongside {@code RoomAvailabilityService}'s cache.
 */
@Component
public class IdempotencyKeyStore {

    private static final Duration TTL = Duration.ofHours(24);

    private final ConcurrentMap<String, StoredResponse> store = new ConcurrentHashMap<>();

    public record StoredResponse(HttpStatus status, AppointmentResponse body, Instant storedAt) {
    }

    public Optional<StoredResponse> get(String key) {
        StoredResponse stored = store.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(stored.storedAt().plus(TTL))) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    public void put(String key, HttpStatus status, AppointmentResponse body) {
        store.put(key, new StoredResponse(status, body, Instant.now()));
    }
}
