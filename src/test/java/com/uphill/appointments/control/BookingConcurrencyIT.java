package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;

import com.uphill.appointments.boundary.api.dto.CreateAppointmentRequest;
import com.uphill.appointments.entity.Patient;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.DoctorRepository;
import com.uphill.appointments.entity.repository.DoctorScheduleRepository;
import com.uphill.appointments.entity.repository.PatientRepository;
import com.uphill.appointments.entity.repository.RoomRepository;
import com.uphill.appointments.entity.repository.SpecialtyRepository;
import com.uphill.appointments.support.TestDataFactory;
import com.uphill.appointments.support.TestcontainersConfig;

/**
 * Proves the no-overbooking guarantee holds under real concurrency: fires
 * concurrent booking requests for the same specialty/slot (only as many
 * doctors as this test itself provisions) and asserts exactly as many
 * succeed as there is doctor capacity, with the rest rejected as 409
 * Conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfig.class)
class BookingConcurrencyIT {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final int DOCTOR_CAPACITY = 2;

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

    private TestDataFactory fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new TestDataFactory(
                specialtyRepository, doctorRepository, doctorScheduleRepository, roomRepository, patientRepository);
    }

    @Test
    void onlyAsManyConcurrentBookingsSucceedAsDoctorCapacityAllows() throws Exception {
        Specialty specialty = fixtures.createSpecialty();
        for (int i = 0; i < DOCTOR_CAPACITY; i++) {
            fixtures.createDoctor(specialty);
        }
        // At least as many rooms as doctors, so doctor capacity is the binding
        // constraint this test is actually proving — not an incidental room shortage.
        for (int i = 0; i < DOCTOR_CAPACITY; i++) {
            fixtures.createRoom();
        }
        Patient patient = fixtures.createPatient();

        OffsetDateTime startsAt = OffsetDateTime.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<HttpStatusCode>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                CreateAppointmentRequest request = new CreateAppointmentRequest(
                        patient.getPatientId(), specialty.getCode(), startsAt.toLocalDate(),
                        startsAt.toLocalTime(), startsAt.getOffset(), null);
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

        assertThat(successCount).isEqualTo(DOCTOR_CAPACITY);
        assertThat(successCount + conflictCount).isEqualTo(CONCURRENT_REQUESTS);
    }

    /**
     * The test above proves the original same-exact-instant race. Since
     * appointments now have variable duration (#024), two candidates can
     * overlap without sharing a {@code starts_at} — this proves the
     * range-exclusion constraint actually blocks *that* race too, under
     * real concurrency, not just the single-threaded proof in
     * AppointmentRepositoryTest.
     */
    @Test
    void onlyOneOfTwoOverlappingButDifferentlyTimedConcurrentBookingsSucceedsForSameDoctor() throws Exception {
        Specialty specialty = fixtures.createSpecialty();
        fixtures.createDoctor(specialty);
        fixtures.createRoom();
        fixtures.createRoom();
        Patient patient = fixtures.createPatient();

        // Fixed mid-day hour, not truncatedTo(HOURS) on "now" — this test needs
        // 90 minutes of same-day headroom, which a late-evening "now" wouldn't have.
        OffsetDateTime baseStart = OffsetDateTime.now().plus(Duration.ofDays(2))
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        // [baseStart, +60min) and [baseStart+30min, +90min) overlap by 30 minutes.
        CreateAppointmentRequest requestA = new CreateAppointmentRequest(
                patient.getPatientId(), specialty.getCode(), baseStart.toLocalDate(),
                baseStart.toLocalTime(), baseStart.getOffset(), 60);
        CreateAppointmentRequest requestB = new CreateAppointmentRequest(
                patient.getPatientId(), specialty.getCode(), baseStart.toLocalDate(),
                baseStart.plusMinutes(30).toLocalTime(), baseStart.getOffset(), 60);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<HttpStatusCode> taskA = () -> {
            ready.countDown();
            start.await();
            return restTemplate.postForEntity("/api/appointments", requestA, String.class).getStatusCode();
        };
        Callable<HttpStatusCode> taskB = () -> {
            ready.countDown();
            start.await();
            return restTemplate.postForEntity("/api/appointments", requestB, String.class).getStatusCode();
        };

        Future<HttpStatusCode> futureA = executor.submit(taskA);
        Future<HttpStatusCode> futureB = executor.submit(taskB);
        ready.await();
        start.countDown();

        HttpStatusCode statusA = futureA.get();
        HttpStatusCode statusB = futureB.get();
        executor.shutdown();

        long successCount = Stream.of(statusA, statusB).filter(HttpStatusCode::is2xxSuccessful).count();
        long conflictCount = Stream.of(statusA, statusB).filter(status -> status.value() == 409).count();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
    }
}
