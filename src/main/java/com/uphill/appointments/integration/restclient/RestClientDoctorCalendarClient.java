package com.uphill.appointments.integration.restclient;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.uphill.appointments.integration.DoctorCalendarClient;

@Component
public class RestClientDoctorCalendarClient implements DoctorCalendarClient {

    private final RestClient restClient;

    public RestClientDoctorCalendarClient(@Qualifier("doctorCalendarRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void reserveSlot(Long doctorId, Instant startsAt, Instant endsAt, UUID appointmentId) {
        restClient.post()
                .uri("/calendar/doctors/{doctorId}/appointments", doctorId)
                .body(new ReserveSlotRequest(appointmentId, startsAt, endsAt))
                .retrieve()
                .toBodilessEntity();
    }

    private record ReserveSlotRequest(UUID appointmentId, Instant startsAt, Instant endsAt) {
    }
}
