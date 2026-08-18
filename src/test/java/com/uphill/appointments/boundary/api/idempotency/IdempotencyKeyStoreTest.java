package com.uphill.appointments.boundary.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;

class IdempotencyKeyStoreTest {

    private final IdempotencyKeyStore store = new IdempotencyKeyStore();

    @Test
    void returnsEmptyForUnknownKey() {
        assertThat(store.get("unknown")).isEmpty();
    }

    @Test
    void putThenGetReturnsStoredResponse() {
        AppointmentResponse body = new AppointmentResponse(
                UUID.randomUUID(), "PAT-0001", "Jane Doe", "CARDIOLOGY", "Dr. Ana", "Room 1",
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(30), "BOOKED");

        store.put("key-1", HttpStatus.CREATED, body);
        Optional<IdempotencyKeyStore.StoredResponse> stored = store.get("key-1");

        assertThat(stored).isPresent();
        assertThat(stored.get().status()).isEqualTo(HttpStatus.CREATED);
        assertThat(stored.get().body()).isEqualTo(body);
    }
}
