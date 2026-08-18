package com.uphill.appointments.boundary.external.kafka;

import java.time.OffsetDateTime;
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
    public void reserveSlot(Long doctorId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId) {
        publish(new DoctorCalendarUpdateEvent(
                appointmentId, doctorId, startsAt, endsAt, DoctorCalendarEventType.RESERVED));
    }

    @Override
    public void releaseSlot(Long doctorId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId) {
        publish(new DoctorCalendarUpdateEvent(
                appointmentId, doctorId, startsAt, endsAt, DoctorCalendarEventType.RELEASED));
    }

    private void publish(DoctorCalendarUpdateEvent event) {
        kafkaTemplate.send(topic, event.appointmentId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish doctor-calendar {} event for appointment {}",
                                event.type(), event.appointmentId(), ex);
                    } else {
                        // The one place that reflects genuine broker acknowledgment, not just
                        // "dispatched" - the send() call above returns immediately either way.
                        log.info(
                                "Published doctor-calendar {} event for appointment {}",
                                event.type(), event.appointmentId());
                    }
                });
    }
}
