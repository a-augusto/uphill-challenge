package com.uphill.appointments.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * Load-tests {@code POST /api/appointments} against a running instance -
 * start it first with {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed},
 * then {@code ./mvnw gatling:test -Pload-test}.
 *
 * <p>Requests are spread across many distinct (specialty, date, time)
 * combinations so the tiny seeded capacity per specialty (2 doctors x 5
 * rooms, from {@code DevDataSeeder}) isn't the bottleneck being measured -
 * this simulation is about throughput/latency, not the no-overbooking
 * guarantee under contention. That's deliberately exercised elsewhere
 * ({@code BookingConcurrencyIT}), not duplicated here. See DECISIONS.md for
 * why this lives under {@code src/load-test/java} rather than {@code src/test/java}.
 */
public class BookingLoadSimulation extends Simulation {

    private static final List<String> SPECIALTIES =
            List.of("CARDIOLOGY", "DERMATOLOGY", "GENERAL_PRACTICE", "PEDIATRICS");
    private static final List<LocalTime> TIME_SLOTS = List.of(
            LocalTime.of(9, 0), LocalTime.of(10, 30), LocalTime.of(13, 0),
            LocalTime.of(14, 30), LocalTime.of(16, 0));
    private static final List<LocalDate> BUSINESS_DAYS = nextBusinessDays(10);

    private static List<LocalDate> nextBusinessDays(int count) {
        List<LocalDate> days = new ArrayList<>();
        // A week out, clear of any "today" edge-case handling in bookOnDay.
        LocalDate date = LocalDate.now().plusDays(7);
        while (days.size() < count) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(date);
            }
            date = date.plusDays(1);
        }
        return days;
    }

    private static String randomSpecialty() {
        return SPECIALTIES.get(ThreadLocalRandom.current().nextInt(SPECIALTIES.size()));
    }

    private static LocalDate randomBusinessDay() {
        return BUSINESS_DAYS.get(ThreadLocalRandom.current().nextInt(BUSINESS_DAYS.size()));
    }

    private static LocalTime randomTimeSlot() {
        return TIME_SLOTS.get(ThreadLocalRandom.current().nextInt(TIME_SLOTS.size()));
    }

    private static String dayOnlyBody(Session session) {
        return """
                {"patientId":"%s","specialtyCode":"%s","date":"%s","offset":"+00:00","durationMinutes":30}
                """.formatted(session.getString("patientId"), randomSpecialty(), randomBusinessDay());
    }

    private static String explicitTimeBody(Session session) {
        return """
                {"patientId":"%s","specialtyCode":"%s","date":"%s","startTime":"%s","offset":"+00:00","durationMinutes":30}
                """.formatted(
                session.getString("patientId"), randomSpecialty(), randomBusinessDay(), randomTimeSlot());
    }

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // 409 (no availability in this candidate slot) is an expected outcome
    // under load, not a failure - only genuinely unexpected statuses fail the check.
    private final ChainBuilder dayOnlyBooking = exec(
            http("book (day-only)")
                    .post("/api/appointments")
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(StringBody(BookingLoadSimulation::dayOnlyBody))
                    .check(status().in(201, 409)));

    private final ChainBuilder explicitTimeBooking = exec(
            http("book (explicit time)")
                    .post("/api/appointments")
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(StringBody(BookingLoadSimulation::explicitTimeBody))
                    .check(status().in(201, 409)));

    private static final List<Map<String, Object>> PATIENTS = IntStream.rangeClosed(1, 30)
            .mapToObj(i -> Map.<String, Object>of("patientId", "PAT-%06d".formatted(i)))
            .toList();

    /**
     * A plain {@link Iterator} feeder is consumed once and crashes the whole
     * engine when it runs out - confirmed live (Gatling stopped the run with
     * "Feeder feed-4 is now empty") the first time this ramped past 30
     * requests with a non-cycling 30-patient iterator. Cycling by hand here
     * instead of reaching for a Gatling feeder-builder strategy - simplest
     * fix for a 30-element list.
     */
    private static Iterator<Map<String, Object>> patientFeeder() {
        return new Iterator<>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Map<String, Object> next() {
                return PATIENTS.get(i++ % PATIENTS.size());
            }
        };
    }

    private final ScenarioBuilder scenario = scenario("Book appointments")
            .feed(patientFeeder())
            .randomSwitch().on(
                    percent(70.0).then(dayOnlyBooking),
                    percent(30.0).then(explicitTimeBooking));

    {
        setUp(
                scenario.injectOpen(
                        rampUsersPerSec(0).to(40).during(30),
                        constantUsersPerSec(40).during(60),
                        rampUsersPerSec(40).to(0).during(15)
                ).protocols(httpProtocol)
        ).assertions(
                global().successfulRequests().percent().gte(95.0),
                global().responseTime().percentile(95).lt(1000),
                global().responseTime().percentile(99).lt(2000)
        );
    }
}
