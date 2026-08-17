package com.uphill.appointments.boundary.external;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.uphill.appointments.boundary.api.dto.AppointmentResponse;
import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Verifies the doctor-calendar and room-reservation RestClient adapters
 * actually fire against the external systems (stubbed here with WireMock)
 * after a booking commits, and that a failure on their end never fails the
 * booking response itself (best-effort, per the after-commit design).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfig.class)
class ExternalIntegrationIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void integrationBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.doctor-calendar.base-url", () -> wireMock.baseUrl());
        registry.add("app.integrations.room-reservation.base-url", () -> wireMock.baseUrl());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void firesCalendarAndRoomReservationCallsAfterBookingCommits() {
        wireMock.stubFor(post(urlPathMatching("/calendar/doctors/.*/appointments")).willReturn(created()));
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));

        ResponseEntity<AppointmentResponse> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMock.verify(postRequestedFor(urlPathMatching("/calendar/doctors/.*/appointments")));
        wireMock.verify(postRequestedFor(urlPathMatching("/rooms/.*/reservations")));
    }

    @Test
    void bookingStillSucceedsWhenExternalCalendarCallFails() {
        wireMock.stubFor(post(urlPathMatching("/calendar/doctors/.*/appointments")).willReturn(serverError()));
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));

        ResponseEntity<AppointmentResponse> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static CreateAppointmentRequest sampleRequest() {
        Instant startsAt = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
        return new CreateAppointmentRequest("PAT-0001", "DERMATOLOGY", startsAt);
    }
}
