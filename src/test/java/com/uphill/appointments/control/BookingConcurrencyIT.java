package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;

import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Proves the no-overbooking guarantee holds under real concurrency: fires
 * concurrent booking requests for the same specialty/slot (only 2 doctors
 * seeded for CARDIOLOGY) and asserts exactly as many succeed as there is
 * doctor capacity, with the rest rejected as 409 Conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfig.class)
class BookingConcurrencyIT {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final int SEEDED_CARDIOLOGY_DOCTORS = 2;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void onlyAsManyConcurrentBookingsSucceedAsDoctorCapacityAllows() throws Exception {
        Instant startsAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<HttpStatusCode>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                CreateAppointmentRequest request = new CreateAppointmentRequest("PAT-0001", "CARDIOLOGY", startsAt);
                return restTemplate.postForEntity("/api/appointments", request, String.class).getStatusCode();
            });
        }

        List<Future<HttpStatusCode>> futures = new ArrayList<>();
        for (Callable<HttpStatusCode> task : tasks) {
            futures.add(executor.submit(task));
        }
        ready.await();
        start.countDown();

        List<HttpStatusCode> results = new ArrayList<>();
        for (Future<HttpStatusCode> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        long successCount = results.stream().filter(HttpStatusCode::is2xxSuccessful).count();
        long conflictCount = results.stream().filter(status -> status.value() == 409).count();

        assertThat(successCount).isEqualTo(SEEDED_CARDIOLOGY_DOCTORS);
        assertThat(successCount + conflictCount).isEqualTo(CONCURRENT_REQUESTS);
    }
}
