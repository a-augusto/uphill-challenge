package com.uphill.appointments.boundary.external.kafka;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.uphill.appointments.boundary.external.DoctorCalendarClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes doctor-calendar updates as Kafka events rather than calling the
 * external system directly. Fire-and-forget is the correct semantics here —
 * unlike room reservation, nothing in our own correctness depends on the
 * calendar system's response, so there's no reason to make this a
 * synchronous, gating call.
 */
@Component
@Slf4j
public class KafkaDoctorCalendarClient implements DoctorCalendarClient {

    private final KafkaTemplate<String, DoctorCalendarUpdateEvent> kafkaTemplate;
    private final String topic;

    public KafkaDoctorCalendarClient(
            KafkaTemplate<String, DoctorCalendarUpdateEvent> kafkaTemplate,
            @Value("${app.kafka.doctor-calendar-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void reserveSlot(Long doctorId, Instant startsAt, Instant endsAt, UUID appointmentId) {
        DoctorCalendarUpdateEvent event = new DoctorCalendarUpdateEvent(appointmentId, doctorId, startsAt, endsAt);
        kafkaTemplate.send(topic, appointmentId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish doctor-calendar update for appointment {}", appointmentId, ex);
                    }
                });
    }
}
