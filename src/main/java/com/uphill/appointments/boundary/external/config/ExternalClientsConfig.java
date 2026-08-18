package com.uphill.appointments.boundary.external.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ExternalClientsConfig {

    @Bean
    public RestClient roomReservationRestClient(
            RestClient.Builder builder,
            @Value("${app.integrations.room-reservation.base-url}") String baseUrl,
            @Value("${app.integrations.room-reservation.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.integrations.room-reservation.read-timeout-ms}") long readTimeoutMs) {
        // Room reservation now gates booking (see BookingAttemptExecutor), so
        // transport flakiness here directly costs a retry attempt instead of
        // being silently swallowed. The JDK HttpClient's HTTP/2 connection reuse
        // has a known flaky interaction with WireMock under concurrent load
        // (RST_STREAM / EOF mid-request) — pinning to HTTP/1.1 avoids it.
        // Explicit connect/read timeouts so a hanging (not just erroring) external
        // system fails fast instead of blocking the request thread indefinitely.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
