package com.uphill.appointments.boundary.external.kafka;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaDoctorCalendarClientTest {

    @Test
    void publishesDoctorCalendarUpdateEventToConfiguredTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, DoctorCalendarUpdateEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(eq("doctor-calendar-updates"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(DoctorCalendarUpdateEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        KafkaDoctorCalendarClient client = new KafkaDoctorCalendarClient(kafkaTemplate, "doctor-calendar-updates");

        UUID appointmentId = UUID.randomUUID();
        Instant startsAt = Instant.now().plus(Duration.ofDays(1));
        Instant endsAt = startsAt.plus(Duration.ofMinutes(30));

        client.reserveSlot(1L, startsAt, endsAt, appointmentId);

        verify(kafkaTemplate).send(
                eq("doctor-calendar-updates"),
                eq(appointmentId.toString()),
                eq(new DoctorCalendarUpdateEvent(appointmentId, 1L, startsAt, endsAt)));
    }
}
