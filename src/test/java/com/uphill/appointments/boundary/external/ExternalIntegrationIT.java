package com.uphill.appointments.boundary.external;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.uphill.appointments.boundary.api.dto.AppointmentResponse;
import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.boundary.api.dto.RoomAvailabilityResponse;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Room;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.DoctorScheduleRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.RoomRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;
import com.uphill.appointments.support.KafkaTestcontainersConfig;
import com.uphill.appointments.support.TestDataFactory;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Verifies the two post-allocation integrations behave the way the data
 * model review settled on: room reservation is synchronous and gates the
 * booking (proven by the 409 test below), doctor-calendar sync is
 * fire-and-forget via a real Kafka broker (proven by consuming the topic).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfig.class, KafkaTestcontainersConfig.class})
class ExternalIntegrationIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void integrationBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.room-reservation.base-url", () -> wireMock.baseUrl());
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private SpecialtyRepository specialtyRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private DoctorScheduleRepository doctorScheduleRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private KafkaContainer kafkaContainer;
    @Value("${app.kafka.doctor-calendar-topic}")
    private String doctorCalendarTopic;

    private Specialty specialty;
    private Room room;
    private Patient patient;

    @BeforeEach
    void setUp() {
        // WireMockExtension's instance is shared across every test method in this
        // class (static field) - reset stubs each time so one test's mapping
        // (e.g. room-reservation always failing) can't leak into another.
        wireMock.resetAll();
        TestDataFactory fixtures =
                new TestDataFactory(specialtyRepository, doctorRepository, doctorScheduleRepository, roomRepository, patientRepository);
        specialty = fixtures.createSpecialty();
        fixtures.createDoctor(specialty);
        room = fixtures.createRoom();
        patient = fixtures.createPatient();

        // Permissive default: the just-created room is always externally available,
        // so tests that don't care about this feature keep behaving as before.
        wireMock.stubFor(get(urlPathMatching("/rooms/available"))
                .willReturn(okJson("{\"roomIds\":[" + room.getId() + "]}")));
    }

    @Test
    void publishesDoctorCalendarUpdateToKafkaAfterBookingCommits() {
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));

        ResponseEntity<AppointmentResponse> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMock.verify(postRequestedFor(urlPathMatching("/rooms/.*/reservations")));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafkaContainer.getBootstrapServers(), "external-integration-it-reserve", "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(doctorCalendarTopic));
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            boolean sawReservedEvent = StreamSupport.stream(records.records(doctorCalendarTopic).spliterator(), false)
                    .anyMatch(record -> record.value().contains(response.getBody().id().toString()));
            assertThat(sawReservedEvent).isTrue();
        }
    }

    @Test
    void bookingFails409WhenRoomReservationRejectedForAllRooms() {
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(serverError()));

        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelReleasesRoomAndPublishesReleasedEventToKafka() {
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));
        wireMock.stubFor(delete(urlPathMatching("/rooms/.*/reservations/.*")).willReturn(noContent()));

        AppointmentResponse booked = restTemplate
                .postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class)
                .getBody();

        ResponseEntity<AppointmentResponse> cancelResponse = restTemplate.postForEntity(
                "/api/appointments/{id}/cancel", null, AppointmentResponse.class, booked.id());

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().status()).isEqualTo("CANCELLED");
        wireMock.verify(deleteRequestedFor(urlPathMatching("/rooms/.*/reservations/.*")));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafkaContainer.getBootstrapServers(), "external-integration-it-cancel", "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(doctorCalendarTopic));
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            boolean sawReleaseEvent = StreamSupport.stream(records.records(doctorCalendarTopic).spliterator(), false)
                    .anyMatch(record -> record.value().contains(booked.id().toString())
                            && record.value().contains("RELEASED"));
            assertThat(sawReleaseEvent).isTrue();
        }
    }

    @Test
    void cancellationSucceedsEvenWhenRoomReleaseCallFails() {
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));
        wireMock.stubFor(delete(urlPathMatching("/rooms/.*/reservations/.*")).willReturn(serverError()));

        AppointmentResponse booked = restTemplate
                .postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class)
                .getBody();

        ResponseEntity<AppointmentResponse> cancelResponse = restTemplate.postForEntity(
                "/api/appointments/{id}/cancel", null, AppointmentResponse.class, booked.id());

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().status()).isEqualTo("CANCELLED");
    }

    @Test
    void bookingOnlyUsesExternallyAvailableRooms() {
        Room excludedRoom = new TestDataFactory(specialtyRepository, doctorRepository, doctorScheduleRepository, roomRepository, patientRepository)
                .createRoom();
        wireMock.stubFor(get(urlPathMatching("/rooms/available"))
                .willReturn(okJson("{\"roomIds\":[" + room.getId() + "]}")));
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));

        ResponseEntity<AppointmentResponse> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), AppointmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMock.verify(postRequestedFor(urlPathMatching("/rooms/" + room.getId() + "/reservations")));
        wireMock.verify(0, postRequestedFor(urlPathMatching("/rooms/" + excludedRoom.getId() + "/reservations")));
    }

    @Test
    void bookingFails409WhenExternalSystemReportsNoAvailableRooms() {
        wireMock.stubFor(get(urlPathMatching("/rooms/available")).willReturn(okJson("{\"roomIds\":[]}")));

        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/appointments", sampleRequest(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void roomAvailabilityEndpointReturnsExternallyAvailableRooms() {
        Room excludedRoom = new TestDataFactory(specialtyRepository, doctorRepository, doctorScheduleRepository, roomRepository, patientRepository)
                .createRoom();
        wireMock.stubFor(get(urlPathMatching("/rooms/available"))
                .willReturn(okJson("{\"roomIds\":[" + room.getId() + "]}")));

        ResponseEntity<RoomAvailabilityResponse[]> response = restTemplate.getForEntity(
                "/api/rooms/availability?date=" + java.time.LocalDate.now().plusDays(1),
                RoomAvailabilityResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(RoomAvailabilityResponse::id)
                .containsExactly(room.getId())
                .doesNotContain(excludedRoom.getId());
    }

    @Test
    void dayOnlyBookingFindsSlotAfterExistingMorningBooking() {
        wireMock.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(created()));
        OffsetDateTime morningStart = OffsetDateTime.now().plus(Duration.ofDays(5))
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        CreateAppointmentRequest morningRequest = new CreateAppointmentRequest(
                patient.getPatientId(), specialty.getCode(), morningStart.toLocalDate(),
                morningStart.toLocalTime(), morningStart.getOffset(), 180);
        ResponseEntity<AppointmentResponse> morningResponse =
                restTemplate.postForEntity("/api/appointments", morningRequest, AppointmentResponse.class);
        assertThat(morningResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        CreateAppointmentRequest dayOnlyRequest = new CreateAppointmentRequest(
                patient.getPatientId(), specialty.getCode(), morningStart.toLocalDate(),
                null, morningStart.getOffset(), 30);
        ResponseEntity<AppointmentResponse> dayOnlyResponse =
                restTemplate.postForEntity("/api/appointments", dayOnlyRequest, AppointmentResponse.class);

        assertThat(dayOnlyResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Compare instants, not toLocalTime(): Postgres round-trips TIMESTAMPTZ in a
        // normalized offset, so the response's offset need not match morningStart's.
        assertThat(dayOnlyResponse.getBody().startsAt()).isAfterOrEqualTo(morningStart.plusMinutes(180));
    }

    private CreateAppointmentRequest sampleRequest() {
        OffsetDateTime startsAt = OffsetDateTime.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
        return new CreateAppointmentRequest(
                patient.getPatientId(), specialty.getCode(), startsAt.toLocalDate(),
                startsAt.toLocalTime(), startsAt.getOffset(), null);
    }
}
