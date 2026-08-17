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
**Superseded by #016.** The spec never asked for patient identity, but the
team wanted it anyway once we started talking through the data model — see
#016 for the promoted design. Leaving this entry in place rather than
deleting it: the reasoning below was sound *given the spec alone*, and the
review trail should show the actual decision path, not just the endpoint.

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

### 014 — Repackaged around Boundary-Control-Entity (BCE), not a correctness fix
Restructured the whole codebase from a conventional layered split
(`api`/`booking`/`domain`/`repository`/`integration`/`notification`/`events`)
into `boundary`/`control`/`entity`. This is a personal-preference call, not a
technical correction — BCE is the pattern I have the most hands-on experience
with, so it's the one I can navigate and extend fastest, and the one I can
defend most precisely in review. Mapping used:
- **Boundary** — anything touching an actor outside the system: `boundary/api`
  (inbound HTTP: controller, DTOs, error handling), `boundary/external`
  (outbound: doctor-calendar/room-reservation ports + RestClient adapters),
  `boundary/notification` (outbound: email).
- **Control** — `BookingService`, `BookingAttemptExecutor`, the booking
  exceptions, `AppointmentBookedEvent` and `PostBookingEventListener` — the
  use-case logic that mediates between Boundary and Entity and never does I/O
  of its own (it calls Boundary interfaces for that).
- **Entity** — `entity/` (domain objects) and `entity/repository` (JPA
  repositories).
- `config/OpenApiConfig` deliberately stayed outside the triad: it's
  API-documentation plumbing, not tied to a specific business actor, so
  forcing it into `boundary` would blur what that package means. Purists
  might disagree; flagging the call explicitly rather than let it look like
  an oversight.

Pure mechanical move — `git mv` + scripted package/import rewrite, then a
full recompile and test run to confirm zero behavioral change (same 19
tests, same results, before and after).

### 015 — Added Failsafe for `*IT.java`, since Surefire was silently skipping them
Found while re-verifying the BCE move: Maven Surefire's default include
patterns (`**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`) don't match
`*IT.java` at all — that suffix is Failsafe's convention, and this project
never had Failsafe configured. Plain `./mvnw test` had been silently running
only 15 of the 19 tests the whole time; `BookingConcurrencyIT` and
`ExternalIntegrationIT` were never actually executing unless explicitly named
with `-Dtest=`. I'd been trusting an earlier `mvn test` run that happened to
use an explicit `-Dtest` filter (which bypasses the default include/exclude
patterns entirely) and mistook that for the real default behavior — worth
owning here rather than glossing over, since it means an earlier "all 19
tests pass" claim in this log wasn't actually verified the way I said it was.
Fixed properly rather than papered over: added the `maven-failsafe-plugin`
bound to the `integration-test`/`verify` goals, and an explicit Surefire
exclude for `**/*IT.java` so the phase split is self-documenting. `./mvnw
verify` is now the one command that genuinely runs the full 19-test suite;
`./mvnw test` intentionally runs only the fast subset.

### 016 — Patient promoted to a real entity, looked up by a business id
Supersedes #009. Once we started talking through the data model, patient
identity turned out to matter: real history, no re-entering demographics on
every visit, and a lookup key that isn't the database's internal surrogate id
(so it survives a DB migration/rebuild and isn't guessable/enumerable the way
a sequential `BIGINT` would be). `Patient` is now a full entity —
`patientId` (business identifier, unique, separate from the DB `id`), name,
email, phone, gender, date of birth, address, emergency contact phone, and a
list of languages spoken.

**Patients are provisioned out of band, not created by booking.** A future
mock-data seeding script (explicitly not built in this pass) will be the real
way patients get into the system; for now a handful are hand-seeded via
Flyway (`V5__seed_patients.sql`), mirroring how doctors/rooms are seeded.
`POST /api/appointments` now takes a `patientId` and *requires* it to already
resolve — an unknown `patientId` is a 404 (`PatientNotFoundException`), not a
patient created on the fly. This is a real behavior change from the
"anyone can book with any details" version: booking someone in now means
they must already exist in the system, which matches how a real clinic
actually works (patients register once, then book many times) far better
than the original embedded-info-per-booking design did.

Field-shape calls, each a small independent tradeoff:
- **Gender as a fixed enum** (`MALE`/`FEMALE`/`OTHER`/`UNSPECIFIED`), not
  free text — easier to query/report on, at the cost of being less flexible
  than a patient-supplied string. Went this way since the values needed to
  live in a real column either way; a closed set is more useful downstream
  (e.g. reporting) than an uncontrolled string would be.
- **Address as a single free-text field**, not a structured
  street/city/postal-code/country embeddable — the spec never asked for
  address at all, so the fuller structure would be effort spent on a field
  nobody's validated the shape of yet. Easy to split later if a real
  requirement shows up.
- **Languages spoken as a list**, via `@ElementCollection` into a
  `patient_language` table — not a single delimited string. Normalized and
  query-friendly without inventing a full `Language` lookup entity, which
  would be over-engineering for "a person speaks a few languages."
- **FK column naming**: `appointment.patient_id` (references `patient.id`,
  the surrogate key) follows the same convention already used for
  `doctor_id`/`room_id`/`specialty_id` — "the id of the patient" — even
  though `patient.patient_id` is a *different* column (the business key).
  Same pattern real schemas use all the time (e.g. a `user_id` FK column
  pointing at a `users` table that also has its own internal identity
  scheme); not renamed for artificial disambiguation.

### 017 — Flyway is structure-only now; seed data moved to a profile-gated runner, and migration history got squashed
User's call, made explicit this session: Flyway migrations should be DDL
only — tables, constraints, indexes — never rows. Up to this point
`V3__seed_specialties_doctors_rooms.sql` and `V5__seed_patients.sql` broke
that rule. Since this project isn't in production yet, both the principle
and a full migration-history squash got applied in the same pass — no reason
to carry forward migrations shaped by the *order features got built in*
(patient table bolted on after appointment already existed, then an ALTER to
link them) once a cleaner end-state is obvious.

**Squash**: six migrations became three, all pure DDL —
`V1__create_specialty_doctor_room_tables.sql` (unchanged),
`V2__create_patient_table.sql` (was V4, moved earlier — patient must exist
before appointment can reference it), `V3__create_appointment_table.sql`
(merges the old V2 + the old V6's ALTER — `patient_id` is part of the table
definition from day one, never bolted on). The old seed migrations were
deleted outright, not folded in. Every `id` column also went back to
`GENERATED ALWAYS AS IDENTITY` (from `BY DEFAULT`) — `BY DEFAULT` existed
specifically so seed migrations could insert explicit ids; nothing does that
anymore, so the stricter default is correct again.

**Squashing pre-prod is safe, but not free**: Flyway tracks applied
migrations by checksum in `flyway_schema_history`, so any local/dev database
that had the old six-migration history applied needs to be reset (drop and
recreate the Postgres container) before the new three-file history will
apply cleanly. One-time cost, worth calling out explicitly since it's exactly
the kind of thing that's invisible until someone's local setup breaks.

**Seeding**: replaced by `seed/DevDataSeeder.java`, a
`@Profile("seed")`-gated `CommandLineRunner` using the existing JPA
repositories — run via `./mvnw spring-boot:run -Dspring-boot.run.profiles=seed`.
Idempotent (skips if any specialty exists), never active without the
profile, so default/production boot is untouched by it. Specialties stay
hardcoded (real domain vocabulary — `CARDIOLOGY` etc. aren't "mock" data in
any sense); doctors, rooms, and patients are generated via
[DataFaker](https://www.datafaker.net/) for realistic bulk demo data. This
is also the "real mock-data generator" flagged as a future task in #016 —
now built, not just planned.

**Test fixtures decoupled too**: the three Testcontainers-backed tests that
used to lean on Flyway-seeded rows (`AppointmentRepositoryTest`,
`BookingConcurrencyIT`, `ExternalIntegrationIT`) now provision their own
minimal fixtures directly via a small `TestDataFactory` test helper, in
their own setup, using the real repositories. Deliberately *not* wired to
the `seed` profile/DataFaker output — that would make test correctness
depend on randomly generated data, which is exactly the kind of flakiness
risk not worth taking. Each test creates uniquely-named specialties/doctors/
rooms/patients (counter-suffixed) so nothing collides with any other test or
run.
