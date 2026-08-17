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
| Room reservation | WireMock stub, synchronous — gates booking |
| Doctor-calendar sync | Apache Kafka — fire-and-forget event |
| Email | Spring Mail + GreenMail (fake SMTP) |
| Testing | JUnit 5, Mockito, Testcontainers (Postgres, Kafka), WireMock, GreenMail |
| Boilerplate | Lombok |

## Architecture at a glance

Package structure follows **Boundary-Control-Entity (BCE)**:

```
com.uphill.appointments
├── boundary/
│   ├── api/            inbound HTTP: REST controller, DTOs, error handling
│   ├── external/         outbound: room-reservation (RestClient) + doctor-calendar (Kafka) ports
│   └── notification/       outbound: email confirmation
├── control/            BookingService, allocation/retry logic (incl. room-reservation
│                       gating), booking exceptions, post-booking event + after-commit fan-out
├── entity/             domain objects (Specialty, Doctor, Room, Patient, Appointment)
│   └── repository/       Spring Data JPA repositories
└── config/             cross-cutting infra config (OpenAPI docs) — outside the BCE triad,
                         not tied to a specific actor
```

- **Boundary** — anything touching an actor outside the system: inbound HTTP
  and outbound integrations (external systems, email).
- **Control** — use-case orchestration and business rules; mediates between
  Boundary and Entity, never does I/O directly.
- **Entity** — domain model + persistence.

**Booking flow:** `POST /api/appointments` → `BookingService` (control)
resolves the specialty, fetches doctors/rooms with that specialty free at
that slot, and tries to persist an appointment for a candidate doctor+room
pair. No-overbooking is enforced by a database unique constraint on
`(doctor_id, starts_at)` and `(room_id, starts_at)` — if a concurrent request
wins the race for a pair, the insert fails and the service just tries the
next pair. **Room reservation is part of that same attempt**: a room must
actually be secured for the appointment to be valid, so
`BookingAttemptExecutor` calls the room-reservation system synchronously,
inside the same transaction as the DB insert — a rejection rolls the attempt
back and `BookingService` tries the next candidate pair, exactly like a lost
DB race. Once a booking commits, an `AppointmentBookedEvent` fires the
doctor-calendar update (published to Kafka, fire-and-forget — nothing in our
own correctness depends on it) and the confirmation email, both
best-effort, neither able to fail the already-successful booking response.

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
| `wiremock` | stub room-reservation API | 8081 |
| `kafka` | doctor-calendar event broker | 9092 |
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
    "patientId": "PAT-0001",
    "specialtyCode": "CARDIOLOGY",
    "startsAt": "2026-08-20T09:30:00Z"
  }'
```

The patient must already exist — `patientId` is a business identifier, not
the database row id, and an unknown one returns 404. `startsAt` must be in
the future and fall on a 30-minute boundary. See **Seeding data** below for
how to get specialties/doctors/rooms/patients into a fresh database.

```bash
curl "http://localhost:8080/api/appointments?specialty=CARDIOLOGY&page=0&size=20"
```

## Seeding data

Flyway migrations are structure only (tables, constraints, indexes) — no
rows. A fresh database has no specialties, doctors, rooms, or patients until
you seed it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

This activates `DevDataSeeder` (`seed/DevDataSeeder.java`), which never runs
otherwise — default/production boot is completely unaffected. It's
idempotent (skips entirely if any specialty already exists), and populates:
the 4 real specialty codes (`CARDIOLOGY`, `DERMATOLOGY`, `GENERAL_PRACTICE`,
`PEDIATRICS`), a couple of doctors per specialty, 5 rooms, and ~30 patients
with realistic mock demographics via [DataFaker](https://www.datafaker.net/)
(business ids `PAT-000001`, `PAT-000002`, ...). Check the app log or query
`GET /api/appointments` after booking to find generated `patientId`s to test
with.

## Running the tests

```bash
./mvnw verify
```

Unit and slice tests (`*Test.java`) run via Surefire in the `test` phase;
integration tests (`*IT.java`) run via Failsafe in the `verify` phase — so
`./mvnw verify` is the single command that runs everything. `./mvnw test`
alone runs the faster subset, skipping `BookingConcurrencyIT` and
`ExternalIntegrationIT` (still requires Docker for `AppointmentRepositoryTest`,
which uses Testcontainers Postgres despite the `Test` suffix).
`BookingConcurrencyIT` is the one worth reading first: it fires concurrent
booking requests at the same specialty/slot and asserts exactly as many
succeed as there is doctor capacity, which is the actual proof the
no-overbooking guarantee holds under real contention rather than just in a
single-threaded unit test.

## Building the container

```bash
docker build -t uphill-appointments .
```

Multi-stage build on `eclipse-temurin:25-*`. The running container still needs
Postgres, the room-reservation system, a Kafka broker, and an SMTP endpoint
reachable — point `spring.datasource.*`, `app.integrations.room-reservation.base-url`,
`spring.kafka.bootstrap-servers`, and `spring.mail.*` at your target environment.

## Known gaps / what's next

- **No auth** on the admin listing endpoint — out of scope per the spec, but
  the first thing to add before any real traffic. See DECISIONS.md #010.
- **No durable retry** if the doctor-calendar Kafka publish fails — logged,
  not retried; the confirmation email has the same gap. A transactional
  outbox would close this; deliberately not built for a one-week scope. See
  DECISIONS.md #007. (Room reservation is different: it gates the booking
  attempt itself now, so a failure there doesn't need durable retry — the
  candidate pair just doesn't become a booking. See DECISIONS.md #018.)
- **Fixed 30-minute slot grid** — the no-overbooking constraint relies on it.
  Variable-duration appointments would need a range-based exclusion
  constraint instead. See DECISIONS.md #005.
- **No patient-provisioning API** — patients only come from the `seed`
  profile's `DevDataSeeder` (see **Seeding data**) or direct DB access;
  there's no registration endpoint. Booking requires a `patientId` to
  already exist. See DECISIONS.md #016, #017.
