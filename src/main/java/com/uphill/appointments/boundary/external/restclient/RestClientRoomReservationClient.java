package com.uphill.appointments.boundary.external.restclient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.uphill.appointments.boundary.external.RoomReservationClient;

@Component
public class RestClientRoomReservationClient implements RoomReservationClient {

    private final RestClient restClient;

    public RestClientRoomReservationClient(@Qualifier("roomReservationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<Long> findAvailableRoomIds(OffsetDateTime date) {
        AvailableRoomsResponse response = restClient.get()
                .uri("/rooms/available?date={date}", date.toLocalDate())
                .retrieve()
                .body(AvailableRoomsResponse.class);
        return response != null ? response.roomIds() : List.of();
    }

    @Override
    public void reserveRoom(Long roomId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId) {
        restClient.post()
                .uri("/rooms/{roomId}/reservations", roomId)
                .body(new ReserveRoomRequest(appointmentId, startsAt, endsAt))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void releaseRoom(Long roomId, UUID appointmentId) {
        restClient.delete()
                .uri("/rooms/{roomId}/reservations/{appointmentId}", roomId, appointmentId)
                .retrieve()
                .toBodilessEntity();
    }

    private record ReserveRoomRequest(UUID appointmentId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    private record AvailableRoomsResponse(List<Long> roomIds) {
    }
}
