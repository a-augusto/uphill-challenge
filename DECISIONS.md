# Decisions & Assumptions Log

Running log of choices made building this take-home, kept honest and in order
so I can walk a reviewer through the "why" behind anything in the diff — not
just the "what."

---

### 001 — Maven over Gradle
Both are fair game, but this isn't the week to relearn a build tool under the
clock. Years of daily-driver Maven experience means faster iteration, fewer
"why won't this resolve" detours, more time actually spent on the domain
problem. Gradle's incremental-build speed is nice but not worth the tax here.

### 002 — Spring Boot 4.0.7
Version pinned specifically for dependency compatibility across the stack
(Flyway, OpenTelemetry, RestClient, WebMVC starters) — avoided chasing
transitive version mismatches that a looser pin would've risked mid-build.

### 003 — Java 25
Latest LTS at time of writing. Picked for the longest support runway, in case
this codebase ends up living on past the interview — and no reason to build on
a shorter-lived line when the newest LTS is available.

### 004 — Doctors, rooms and specialties are Flyway-seeded, not admin-managed
The spec says the system auto-assigns doctors and rooms — it never asks for
CRUD screens to manage them. Building an admin API for entities nobody's
allowed to edit through the product would be effort spent on a corner nobody
asked about. A fixed seed (`V3__seed_specialties_doctors_rooms.sql`) covering
4 specialties, 9 doctors, 5 rooms is enough to exercise every code path,
including the no-overbooking constraint under real contention.

### 005 — No-overbooking via a DB unique constraint + optimistic retry, not row locking
Two things can't happen: the same doctor booked twice at the same instant, or
the same room booked twice at the same instant. I could have reached for
`SELECT ... FOR UPDATE` on doctor/room rows before deciding, but at "thousands
of requests/hour" that means every booking attempt queues behind a lock held
by an unrelated request for a totally different slot — throughput dies for no
reason, and it opens the door to deadlocks the moment two transactions lock a
doctor and a room in different orders.

Instead, `appointment` carries two plain `UNIQUE (doctor_id, starts_at)` /
`UNIQUE (room_id, starts_at)` constraints (see `V2__create_appointment_table.sql`).
`BookingService` picks a candidate doctor+room pair, tries to insert
(`saveAndFlush`, so the constraint fires immediately rather than at commit),
and if it loses the race it just tries the next pair
(`DataIntegrityViolationException` → retry, capped at `MAX_BOOKING_ATTEMPTS`).
No lock is ever held across a decision — the database itself is the single
source of truth on whether a slot is free, and losing a race costs one extra
round-trip, not a blocked connection.

**Known tradeoff:** the constraint is keyed on the exact `starts_at` instant,
not a time range — it relies on the app enforcing a fixed 30-minute slot grid
(`BookingService.validateSlot`). If appointments ever got variable durations,
this would need to become a Postgres exclusion constraint over a `tstzrange`.
Not needed for this spec, so not built.

### 006 — External systems (doctor calendar, room reservation) as real HTTP calls against WireMock, not in-process fakes
The brief is explicit: "the code should be written as if it were a real
system, with clear abstractions and proper integration points." An in-process
fake `DoctorCalendarClient` that just logs and returns would satisfy the
interface but not the intent — it would never exercise serialization, HTTP
error handling, or timeouts, and a teammate reading the code couldn't tell the
difference between "this is stubbed for the demo" and "this is how we'd wire
the real Epic/Cerner-style calendar API."

So `DoctorCalendarClient` / `RoomReservationClient` are ports, backed by
`RestClient`-based adapters that make genuine HTTP calls — to a WireMock
container in local dev (`docker-compose.yaml`) and to a `WireMockExtension` in
integration tests. Swapping the stub for the real system in production is a
one-line base-URL change, nothing else in the codebase moves.

### 007 — Post-booking side effects fire after-commit via a Spring event, not a transactional outbox
Three things need to happen once a booking is confirmed: calendar update, room
reservation, confirmation email. All three call out to systems that can be
briefly unavailable, and none of them should be allowed to roll back a booking
that already succeeded, or fire for one that's about to be rolled back by a
losing race.

The textbook "correct" answer here is a transactional outbox — write the
intent to an outbox table in the same transaction as the booking, and have a
separate poller/dispatcher deliver it with guaranteed retry. That's the right
call for a system that must never silently lose a notification. It's also a
new table, a poller, and meaningfully more moving parts to build, test, and
explain in a one-week solo build with no stated durable-delivery requirement.

Instead: `BookingService` publishes an `AppointmentBookedEvent`, and
`PostBookingEventListener` picks it up via
`@TransactionalEventListener(phase = AFTER_COMMIT)` — guaranteeing external
systems only ever hear about appointments that actually made it to the
database. Each of the three actions runs independently, wrapped in its own
try/catch: a failure is logged, not retried, and never turns into a 500 for
the patient (the booking already succeeded — that's the fact that matters).
**Documented gap:** if the calendar call fails, nothing currently retries it.
The honest answer for "what would you add before this actually ships" is the
outbox — flagging it here rather than pretending the simplification isn't one.

### 008 — Confirmation email via GreenMail, not a no-op logger
Same reasoning as external systems: the code should look like it's really
sending mail, because in production it is. `EmailNotificationService` uses
Spring's real `JavaMailSender`; locally and in tests it points at GreenMail —
a fake SMTP server, run as a docker-compose container for manual testing and
as a JUnit 5 extension in `EmailNotificationServiceIT`. Only `spring.mail.host`
/`spring.mail.port` change between this and a real SES/SendGrid SMTP relay.

### 009 — Patient modeled as an embedded value object, not a standalone entity
The spec never asks the system to recognize a returning patient, look up their
appointment history, or manage patient identity — every request supplies
patient info fresh. Introducing a `Patient` table with matching/dedup logic
would be solving a problem the brief didn't pose. `PatientInfo` is a JPA
`@Embeddable` living directly on `Appointment`; if patient identity becomes a
real requirement later, promoting it to a first-class entity is a contained,
well-understood migration.

### 010 — "Admin" listing endpoint has no auth
The spec asks for an endpoint "for admin use" but says nothing about
authentication, authorization, or who else can call the API. Bolting on a
security scheme (API keys, JWT, whatever) would be inventing requirements
rather than meeting them, and would eat time better spent on the actual
scheduling logic the brief is testing. `GET /api/appointments` is reachable
by anyone who can reach the service — "admin" here describes the endpoint's
*purpose*, not an enforced role. This is the one place I'd most want to talk
through with the team before this goes anywhere near production traffic.

### 011 — Admin listing built on Spring Data Specifications, not a hand-rolled JPQL "optional filter" query
First pass used a single `@Query` with `(:param is null or ...)` branches to
make each filter optional. That pattern breaks Postgres's JDBC parameter-type
inference the moment a parameter (an `Instant`, here) only ever appears in a
bare `? IS NULL` comparison with no other typed context in the same prepared
statement — Postgres refuses to guess the type and the query 500s
(`could not determine data type of parameter $3`). Discovered this by actually
running the app end-to-end against real Postgres via docker-compose, not just
through unit/slice tests (which mock the repository and never touch this SQL
at all). Rebuilt `GET /api/appointments` on `JpaSpecificationExecutor` instead
— each optional filter is its own `Specification`, only included in the query
when its parameter is present, so an unset filter simply isn't in the
generated SQL rather than being a type-ambiguous bound parameter. General
lesson: the `(:param IS NULL OR ...)` pattern is a real Postgres/JDBC
footgun — worth flagging if it comes up on other services.

### 012 — Booking's retry-on-conflict transaction split into its own bean (not a private method on BookingService)
The original design had `BookingService.attemptBook(...)` annotated
`@Transactional(propagation = REQUIRES_NEW)`, called from a private method on
the same class. That annotation silently did nothing: Spring's
`@Transactional` only takes effect through the proxy Spring wraps the bean
in, and a call from one method to another *on the same instance* never goes
through that proxy — it's a plain Java call. So every retry after the first
lost race ran inside the *same* transaction as the first (failed) attempt,
which Postgres had already marked aborted, and every subsequent insert failed
with "current transaction is aborted" instead of getting a clean shot. This
never showed up in the Mockito-based unit test (mocks don't care about
transaction boundaries) — only surfaced once `BookingConcurrencyIT` ran
against real Postgres with actual concurrent requests. Fixed by moving the
per-attempt insert into its own `BookingAttemptExecutor` bean, so the call
from `BookingService` genuinely crosses a proxy boundary and `REQUIRES_NEW`
means what it says.

### 013 — AppointmentBookedEvent published from inside the attempt's own transaction, not from BookingService
Follow-on from #012. First fix moved the retry logic to its own bean but kept
event publishing in `BookingService`, wrapped in a fresh `@Transactional` just
so `@TransactionalEventListener(AFTER_COMMIT)` had a transaction to bind to
(that listener silently drops events published with no transaction active).
Under `BookingConcurrencyIT`'s 10-concurrent-request load this deadlocked the
connection pool: the outer transaction held one connection for the entire
doctor/room selection + retry loop, while each attempt inside it needed a
*second*, concurrently — with 10 requests each holding 1 of Hikari's 10
default connections, nothing could ever get its second connection. Fixed by
publishing the event from the end of `BookingAttemptExecutor.attemptBook()`
itself, tied to the one attempt that actually succeeds — no outer transaction
needed at all, and every DB interaction for a given attempt (including the
event that depends on it) lives inside one short-lived transaction. Also only
surfaced under real concurrent load against real Postgres, not in any mocked
test — this build leaned on `BookingConcurrencyIT` running against Docker
twice: once to prove the retry logic, once more to catch what fixing the
retry logic broke.
