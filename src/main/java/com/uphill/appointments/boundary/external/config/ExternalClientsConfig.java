package com.uphill.appointments.boundary.external.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ExternalClientsConfig {

    @Bean
    public RestClient doctorCalendarRestClient(
            RestClient.Builder builder,
            @Value("${app.integrations.doctor-calendar.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient roomReservationRestClient(
            RestClient.Builder builder,
            @Value("${app.integrations.room-reservation.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
