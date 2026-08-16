# Uphill Challenge — Appointments Service

Take-home solution for Uphill Health's Senior Developer challenge: a Spring
Boot service that schedules medical appointments in Portugal. The patient
picks a specialty and a timeslot; the system auto-assigns an available doctor
and room, guarantees no doctor or room is ever double-booked, and confirms the
booking to the patient by email — while updating the doctor's calendar and
reserving the room in (stubbed) external systems.

## Stack

| Concern | Choice |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4.0.7 |
| Build | Maven |
| Database | PostgreSQL |
| Migrations | Flyway |
| API docs | springdoc-openapi (Swagger UI) |
| Observability | OpenTelemetry |
| External systems | WireMock (doctor calendar, room reservation) |
| Email | Spring Mail + GreenMail (fake SMTP) |
| Testing | JUnit 5, Mockito, Testcontainers (Postgres), WireMock, GreenMail |
| Boilerplate | Lombok |

## Architecture at a glance

```
com.uphill.appointments
├── domain/         entities: Specialty, Doctor, Room, PatientInfo, Appointment
├── repository/      Spring Data JPA repositories
├── booking/          BookingService — doctor/room allocation + retry logic
├── api/               REST controller, request/response DTOs, error handling
├── integration/        ports to external systems + RestClient adapters
├── notification/        email confirmation
└── events/               after-commit fan-out to integration + notification
```

**Booking flow:** `POST /api/appointments` → `BookingService` resolves the
specialty, fetches doctors/rooms with that specialty free at that slot, and
tries to persist an appointment for a candidate doctor+room pair. No-overbooking
is enforced by a database unique constraint on `(doctor_id, starts_at)` and
`(room_id, starts_at)` — if a concurrent request wins the race for a pair, the
insert fails and the service just tries the next pair. Once a booking commits,
an `AppointmentBookedEvent` fires the doctor-calendar update, room reservation,
and confirmation email — each independently, each best-effort, none of them
able to fail the booking response.

See [`DECISIONS.md`](./DECISIONS.md) for the reasoning behind every
non-obvious call (why a unique constraint instead of row locking, why
WireMock instead of an in-process fake, why an after-commit event instead of
a transactional outbox, and more) — read it alongside this README.

## Running locally

```bash
./mvnw spring-boot:run
```

Spring Boot's Docker Compose integration auto-starts everything the app needs
on boot (see `docker-compose.yaml`):

| Service | Purpose | Port |
|---|---|---|
| `postgres` | application database | random (auto-wired) |
| `wiremock` | stub doctor-calendar + room-reservation APIs | 8081 |
| `greenmail` | fake SMTP server for confirmation emails | 3025 (SMTP), 8082 (web UI) |
| `grafana-lgtm` | OpenTelemetry collector + dashboards | 3000 |

Once running:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Sent emails (GreenMail web UI): `http://localhost:8082`

### Example request

```bash
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -d '{
    "patientName": "Maria Silva",
    "patientEmail": "maria.silva@example.com",
    "patientPhone": "+351912345678",
    "specialtyCode": "CARDIOLOGY",
    "startsAt": "2026-08-20T09:30:00Z"
  }'
```

`startsAt` must be in the future and fall on a 30-minute boundary. Seeded
specialty codes: `CARDIOLOGY`, `DERMATOLOGY`, `GENERAL_PRACTICE`, `PEDIATRICS`
(see `V3__seed_specialties_doctors_rooms.sql`).

```bash
curl "http://localhost:8080/api/appointments?specialty=CARDIOLOGY&page=0&size=20"
```

## Running the tests

```bash
./mvnw test
```

Most tests are plain unit/slice tests and need nothing extra. A few are
integration tests that spin up real infrastructure via Testcontainers
(`AppointmentRepositoryTest`, `BookingConcurrencyIT`, `ExternalIntegrationIT`)
— **these require Docker running locally.** `BookingConcurrencyIT` is the one
worth reading first: it fires concurrent booking requests at the same
specialty/slot and asserts exactly as many succeed as there is doctor
capacity, which is the actual proof the no-overbooking guarantee holds under
real contention rather than just in a single-threaded unit test.

## Building the container

```bash
docker build -t uphill-appointments .
```

Multi-stage build on `eclipse-temurin:25-*`. The running container still needs
Postgres, WireMock, and an SMTP endpoint reachable — point `spring.datasource.*`,
`app.integrations.*.base-url`, and `spring.mail.*` at your target environment.

## Known gaps / what's next

- **No auth** on the admin listing endpoint — out of scope per the spec, but
  the first thing to add before any real traffic. See DECISIONS.md #010.
- **No durable retry** if the doctor-calendar or room-reservation call fails
  after a booking commits — logged, not retried. A transactional outbox would
  close this gap; deliberately not built for a one-week scope. See
  DECISIONS.md #007.
- **Fixed 30-minute slot grid** — the no-overbooking constraint relies on it.
  Variable-duration appointments would need a range-based exclusion
  constraint instead. See DECISIONS.md #005.
