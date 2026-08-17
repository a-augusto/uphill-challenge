package com.uphill.appointments.boundary.external.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the doctor-calendar topic so Spring's KafkaAdmin creates it eagerly
 * at startup, rather than relying on broker-side auto-creation on first send —
 * that race is exactly what a short producer {@code max.block.ms} (chosen so
 * an unreachable broker fails fast) would lose against on a cold broker.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic doctorCalendarUpdatesTopic(@Value("${app.kafka.doctor-calendar-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }
}
