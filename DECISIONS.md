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

**Update (see #020):** once cancellation existed, the plain `UNIQUE`
constraints became partial unique indexes filtered to `status = 'BOOKED'` —
the range-vs-instant tradeoff above is unchanged and still applies.

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

### 018 — Room reservation is now synchronous and gates booking; doctor-calendar sync moved to Kafka
Came out of reviewing the data model together: both external notifications
used to work the same way (fire-and-forget, after commit, best-effort). That
was wrong for room reservation specifically — a room must actually be
secured for the appointment to be valid, so its answer should gate the
booking exactly like our own DB unique constraint does. Doctor-calendar sync
has no such correctness requirement, so it moved the other direction: off
REST entirely, onto a real Kafka event, since nothing in our own correctness
depends on it and a message broker is the more honest shape for a
notification nobody needs to wait on.

**Room reservation**: the call moved from `PostBookingEventListener`
(after-commit, best-effort) into `BookingAttemptExecutor.attemptBook()`
itself — still inside the same `REQUIRES_NEW` transaction as the DB insert.
DB check first (cheap, local), then the external call; if it throws, a new
`RoomReservationFailedException` rolls back the transaction and
`BookingService`'s existing retry loop just tries the next candidate pair,
exactly like a lost race on the unique constraint. This deliberately
reintroduces the "network I/O inside a DB transaction" pattern #007
specifically avoided for calendar/email — the right call here, since room
correctness is load-bearing in a way calendar sync never was.

**Doctor calendar**: `KafkaDoctorCalendarClient` replaces
`RestClientDoctorCalendarClient` behind the same `DoctorCalendarClient` port
— `PostBookingEventListener`'s calendar call didn't need to change at all,
only the adapter did, which is exactly what the port/adapter split from #006
was for. Publishes a `DoctorCalendarUpdateEvent` to a
`doctor-calendar-updates` topic via `KafkaTemplate`; still called from the
existing after-commit best-effort fan-out. "Mock events" here means a real
Testcontainers-backed Kafka broker in tests and a real single-node broker in
`docker-compose.yaml` for local dev — not a fake — matching how every other
external dependency in this build is tested (DECISIONS #006's reasoning
applies again: exercise the real integration code path, don't bypass it).

**Three real bugs found wiring this up, each worth its own line**, because
each one only showed up under conditions the unit tests don't create:
- *JDK HttpClient's HTTP/2 vs WireMock, under concurrency.* Once room
  reservation gated booking, `BookingConcurrencyIT`'s 10 concurrent requests
  started intermittently losing bookings to `EOFException`/`RST_STREAM`
  errors — a known flaky interaction between the JDK `HttpClient`'s HTTP/2
  connection reuse and WireMock under load. Invisible before, because a
  flaky post-commit notification was silently swallowed; load-bearing now,
  it directly cost successful bookings. Fixed by pinning the room-reservation
  `RestClient` to HTTP/1.1 (`ExternalClientsConfig`).
- *Kafka producer default timeouts are enormous for a fire-and-forget call.*
  Default `max.block.ms` is 60s — since the calendar call still happens
  synchronously within the after-commit listener (same request thread, after
  commit but before the response returns), an unreachable broker was holding
  every response hostage for up to a minute. Capped at 2s
  (`spring.kafka.producer.properties.max.block.ms`) — long enough for a
  healthy broker, short enough that "broker's down" fails fast and gets
  logged like any other best-effort failure.
- *Eager topic creation traded one slow path for another.* Declaring the
  topic via a `NewTopic` bean (so a real broker doesn't need auto-creation
  racing against that same 2s producer timeout) means `KafkaAdmin` also
  tries to reach the broker at startup — and its own default timeouts are
  just as generous (~30s), so *every* test booting the full app context
  without a real broker configured started paying a ~40s startup tax, not
  just Kafka-specific ones. Capped `spring.kafka.admin.properties.*`
  timeouts at 3s too, for the same fail-fast reason as the producer.

None of these three were reachable from a mocked unit test — all three only
exist under real concurrency, a real (or deliberately absent) broker, or
real Spring context startup. Exactly the reason this build leans on
Testcontainers/WireMock/real infra instead of fakes wherever the interaction
under test is the point.

### 019 — Instant replaced with OffsetDateTime everywhere
User's call: `OffsetDateTime` throughout instead of `Instant`, for every
timestamp field/param/DTO in the codebase (`startsAt`/`endsAt`/`createdAt` on
`Appointment`, the booking API's request/response DTOs, the Kafka event
payload, the external client ports). Mechanical, no behavior change — DB
columns were already `TIMESTAMPTZ`, which Hibernate maps to `OffsetDateTime`
natively, and JSON (de)serialization is unaffected (Jackson's `JavaTimeModule`
handles both the same way, ISO-8601 with offset). Two real call-site fixes
needed along the way, since `OffsetDateTime`'s API isn't a drop-in superset
of `Instant`'s: `Instant.getEpochSecond()` → `OffsetDateTime.toEpochSecond()`
(`BookingService`'s slot-boundary check), and `Instant.atZone(ZoneId)` →
`OffsetDateTime.atZoneSameInstant(ZoneId)` (the confirmation email's
Lisbon-local date formatting).

### 020 — Appointment cancellation: release doesn't gate the way reserve does
Second half of the lifecycle work #018 set up. `POST /api/appointments/{id}/cancel`
(`CancellationService`) marks the appointment `CANCELLED`, sets `cancelledAt`,
and — this is the load-bearing design call — publishes an
`AppointmentCancelledEvent` for a symmetric release fan-out (room, calendar,
email), all best-effort, none of them gating the cancellation.

**Why release doesn't mirror reserve's gating behavior**, even though they're
symmetric operations on the same external system: reserving a room is
load-bearing for *booking* — an appointment isn't valid without one, so a
rejection has to stop the booking. Releasing has no equivalent stake for
*cancellation* — the patient's cancellation is already correct and complete
the moment our own DB says so; the external system finding out is a
courtesy, not a precondition. Making a patient's cancellation depend on a
downstream system's uptime would be trading a real user-facing failure mode
for a rare, silently-recoverable one. So: `CancellationService.cancel()` is a
plain `@Transactional` method (no `BookingAttemptExecutor`-style split needed
— nothing here is a retried candidate pair), and the release fan-out lives in
the same after-commit best-effort listener as booking's confirm-side actions.

**Listener renamed**, honestly rather than papering over scope creep:
`PostBookingEventListener` → `AppointmentEventListener`, now handling both
`AppointmentBookedEvent` and `AppointmentCancelledEvent` — it's the same
after-commit fan-out mechanism serving two lifecycle events, not two
unrelated concerns bolted together.

**Schema**: `appointment` gained `cancelled_at`, and the two `UNIQUE`
constraints from #005 became partial unique indexes
(`WHERE status = 'BOOKED'`) — a cancelled row no longer holds its doctor/room
slot hostage. Proven by a repository test that books, cancels, and rebooks
the exact same doctor+room+slot successfully. Edited `V3` in place rather
than adding a new migration — still pre-prod, same standing permission as
the earlier squash.

**Reschedule** is deliberately *not* a new endpoint: cancel, then a fresh
`POST /api/appointments`. Two well-tested, independent operations composed
by the client is simpler and more honest than a combined endpoint that would
just be doing the same two things internally — no atomicity requirement was
stated, and inventing one would be scope creep in the other direction.

### 021 — External system now gates the room *candidate list*, not just the final reservation
Until now, "which rooms are candidates" was purely our own DB's opinion
(`Room.active` + no conflicting appointment at the slot); the external
room-reservation system only ever got asked about one specific room, at the
very end, via `reserveRoom`. That's not how a real hospital's room inventory
would work — rooms are shared with other systems (maintenance holds, other
departments booking directly against the facilities system) that our DB has
no visibility into. So the external mock now also exposes a day-level
availability check, and `BookingService` asks it *before* iterating
candidates, not just when reserving one.

New `control/RoomAvailabilityService.availableRoomsOn(LocalDate)` — asks
`RoomReservationClient.findAvailableRoomIds(date)`, intersects with our own
`Room.active` set. Deliberately **day granularity**, not per-slot or
per-week: it's exactly what `BookingService` needs (a slot's date), and a
week view has no consumer yet — building it speculatively would be exactly
the kind of premature abstraction this project tries to avoid. `BookingService`
now depends on this service instead of `RoomRepository` directly, and layers
its own existing per-slot conflict check on top of the (now externally
pre-filtered) candidate list.

**The final `reserveRoom` call during `BookingAttemptExecutor.attemptBook`
remains the actual gate** — this day-level check is a coarser, cheaper
pre-filter layered in front of it, not a replacement. It can't protect
against a same-slot race between two concurrent requests (that's still
`reserveRoom` synchronously inside the DB transaction, per #018); what it
protects against is booking against a room the external system already
considers unavailable for reasons entirely outside our own appointment
table.

**Failure mode**: if the availability check itself fails (network error,
non-2xx), `RoomAvailabilityService` throws
`RoomAvailabilityCheckFailedException` — a *different* exception from
`RoomReservationFailedException` (that one means "one specific reservation
attempt was rejected"; this one means "couldn't even determine candidates").
`BookingService` catches it and rethrows as `AppointmentAllocationException`
(409), consistent with every other "couldn't find a valid room" outcome.
The same exception surfaces at `GET /api/rooms/availability` (see below) as
**503**, not 409 — there, it's not a booking failure, it's a dependency
being down, and that distinction is worth keeping visible to a caller.

**Also exposed as its own read endpoint**: `GET
/api/rooms/availability?date=...`, backed by the same
`RoomAvailabilityService`, letting a caller preview room capacity before
attempting a booking — not required by the booking flow itself, but a small
addition that demonstrates the same external-system-as-source-of-truth
pattern as a standalone capability, which is a realistic shape for this kind
of integration in production.

**Test/fixture fallout**: `BookingConcurrencyIT` and local/dev both rely on
the docker-compose WireMock container's static mappings
(`wiremock/mappings/room-reservation-availability.json`, returns the 5
seeded room ids) rather than per-test stubs, so that file needed a new
mapping. `ExternalIntegrationIT` now stubs a permissive "the room I just
created is available" response in `@BeforeEach` so its four pre-existing
tests keep behaving as before, plus new tests proving the external system
actually narrows candidates and that an empty external response fails
booking with 409 even when the DB alone would have allowed it.

### 022 — Appointments get flexible day/duration instead of a fixed 30-minute grid (business requirement, not yet implemented)
Requirement change, captured as stated so it can be designed properly before
any code changes — this entry records the rule, not an implementation:

- Rooms (and by extension, doctors) must be bookable at **any time of day**,
  not just standard business hours — this models a hospital, which doesn't
  close.
- The caller chooses both the **day** and the **duration** of an
  appointment, not just a slot from a fixed grid.
- **Max duration: 8 hours** — a healthcare professional's standard workday,
  no overtime.
- **Defaults when only a day is given** (no explicit time): standard slot
  duration of **30 minutes**, placed within **extended business hours,
  9am–6pm**.

This directly supersedes the "fixed 30-minute slot grid" gap flagged back in
#005, which already called out that variable-duration appointments would
need a range-based exclusion constraint instead of the current exact-match
unique index — that's now the concrete next step, not a hypothetical one.
Not designed or built yet; logged now per explicit request, to be scoped as
its own step before implementation starts.

### 023 — Flexible-duration appointments: resolved design questions
Ten open questions from #022 were worked through one at a time before any
implementation. Still not built — this entry records the answers so the
eventual implementation plan has a settled spec to build against.

- **Start-time granularity: 15-minute grid.** Start times must align to
  :00/:15/:30/:45. Keeps the day-search space bounded and predictable, while
  being finer than today's 30-minute grid.
- **Minimum duration: 15 minutes.** Matches the start grid — the smallest
  meaningful unit at that granularity.
- **Duration granularity: multiples of 15 minutes**, up to the 8-hour max
  from #022. One unit (15 min) now governs start-time alignment, minimum
  duration, and duration step — a single knob instead of three independent
  ones.
- **No buffer/turnover time between appointments in the same room.**
  Back-to-back is allowed — one appointment can start the instant the
  previous one in that room ends. Room cleaning/turnover is an operational
  concern outside this system's scope, not modeled. Keeps the eventual range-
  exclusion constraint a plain overlap check (touching ranges don't exclude).
- **Doctors get working hours now, not deferred.** Doctor availability was
  going to eventually need this anyway; folding it into this step means the
  "any time" room requirement and doctor availability are designed together
  instead of the doctor side becoming its own follow-up gap.
- **Doctor hours model: per-day-of-week schedule**, not a single daily
  window. A new entity (working title `DoctorSchedule`) — one working-hours
  range per day-of-week per doctor; a day with no entry means the doctor is
  off that day. Bigger than a two-column addition to `Doctor`, but it's the
  realistic shape (weekends off, half-days, etc.) and avoids modeling
  something admittedly wrong just to save a table.
- **Timezone for business-hours defaults and doctor schedules: whatever
  offset the caller sends**, not a fixed Europe/Lisbon anchor. "9am" in the
  default window and in a doctor's schedule is evaluated against the
  `OffsetDateTime`'s own offset, not converted to a fixed hospital timezone.
- **Request shape: `date` + optional `startTime` + optional
  `durationMinutes`**, replacing the single `startsAt` on
  `CreateAppointmentRequest`. Explicit absence (missing `startTime` /
  `durationMinutes`) rather than a sentinel value — a midnight `startsAt`
  can't mean "no time given" once midnight is itself a valid bookable time
  under the "any time" rule.
- **Day-only fallback: fails within the 9am–6pm default window**, does not
  silently expand to the full day. If nothing fits in business hours that
  day, the caller gets a 409 and has to explicitly ask for a time outside
  9–6 — auto-expanding would make the default meaningless as an actual
  boundary rather than a soft preference.
- **Day-only search strategy: compute each active doctor's and room's free
  time windows for the day upfront (from existing bookings + doctor
  schedule), then intersect** to find (doctor, room, time) candidates that
  fit the requested/default duration — not blind first-fit stepping through
  15-minute increments. More work to design than reusing the existing
  candidate-pair retry loop as an inner step, but avoids an arbitrary
  attempts cap on top of an already-bounded search space, and is honest
  about being a real interval-intersection problem rather than trial and
  error.

### 024 — Flexible-duration appointments, Stage 1: built (explicit time only)
Implements the schema/duration/doctor-schedule half of #023. Stage 2
(day-only search) is a separate follow-up, not built here.

**No-overbooking moved from a unique index to a range-exclusion
constraint.** The old partial `UNIQUE (doctor_id, starts_at)` /
`UNIQUE (room_id, starts_at)` indexes only worked because every appointment
had the same duration — two appointments with different `starts_at` can now
overlap (9:00–9:45 and 9:30–10:00). Replaced with:
```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE appointment ADD CONSTRAINT excl_doctor_overlap
    EXCLUDE USING gist (doctor_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status = 'BOOKED');
-- same for room_id
```
`btree_gist` lets GiST support equality on a bigint alongside the range `&&`
operator — without it, Postgres can't build this index type over a mixed
scalar+range key. `tstzrange`'s default `[)` bounds (inclusive start,
exclusive end) are exactly the no-buffer decision from #023: an appointment
ending at 10:00 and one starting at 10:00 in the same room don't overlap, so
back-to-back booking is allowed. No new column: the range is computed from
the existing `starts_at`/`ends_at` at constraint-check time, so nothing
changed in how `Appointment` maps to the table. Edited `V3` in place, same
standing pre-prod permission as every prior schema change this session.
Postgres reports exclusion violations under the same integrity-violation
SQLSTATE class as unique violations, so Spring still translates them to
`DataIntegrityViolationException` — `BookingService`'s retry loop needed no
change to keep catching them.

**Doctors now have working hours.** New `doctor_schedule` table (own
migration, `V4` — a new table, not an edit to existing structure): one row
per doctor per day-of-week they work, `start_time`/`end_time`; no row for a
day means the doctor is off. `BookingService.availableDoctors` now filters
candidates to doctors whose schedule for `startsAt`'s day-of-week fully
contains the requested `[startsAt, endsAt)` range, on top of the existing
active + not-already-booked filters. A doctor with no schedule row that day
isn't a validation error, it's just not a candidate — same treatment as
"already booked."

**Appointments can't cross midnight.** A single day-of-week schedule row
can't express a range spanning two calendar days, so `BookingService.book`
explicitly rejects `startsAt`/`endsAt` falling on different dates
(`SlotValidationException`). Documented simplification, not an oversight —
crossing midnight would need either a two-row schedule lookup or a
different schedule representation entirely, and nothing in the stated
requirements needs it yet.

**Bug fixed along the way**: the old `findBookedDoctorIdsAtSlot`/
`findBookedRoomIdsAtSlot` queries had no `status = 'BOOKED'` filter at all —
a cancelled appointment at the same exact instant could have incorrectly
excluded a doctor/room from candidacy at the application-filter level (the
DB constraint itself was never affected, since it was already scoped to
`BOOKED` rows). The new overlap queries
(`findBookedDoctorIdsOverlapping`/`findBookedRoomIdsOverlapping`) filter on
status explicitly.

**Duration/grid rules enforced in `BookingService`, not the DB**: 15-minute
start-time grid, 15-minute minimum, 15-minute duration multiples, 8-hour
max — all via the existing `SlotValidationException` (400), no new
exception type needed. Default duration is 30 minutes when
`durationMinutes` is omitted.

**Request shape**: `CreateAppointmentRequest` replaced `startsAt` with
`date` + `startTime` (both required for this stage) + `offset` (required,
explicit — consistent with #019's stance on never defaulting an
`OffsetDateTime`'s zone) + `durationMinutes` (optional, defaults to 30).
`startTime` becomes optional when Stage 2 (day-only search) ships.

**Test fixture change**: `TestDataFactory.createDoctor` now also inserts a
permissive all-week, all-day schedule — otherwise every existing
integration test that creates a doctor would suddenly find zero candidates
purely because of this new gating dimension, unrelated to what those tests
actually check.

### 025 — Flexible-duration appointments, Stage 2: day-only search built
Implements the other half of #023: `CreateAppointmentRequest.startTime` is
now genuinely optional. Omitting it routes to
`BookingService.bookOnDay(specialtyCode, patientId, date, offset,
durationMinutes)`, which searches for a free doctor/room/time within
extended business hours (9am–6pm) instead of requiring an exact instant.

**Interval math extracted into a pure, dependency-free helper**
(`control/Interval`, `control/FreeWindowFinder`) rather than folded into
`BookingService` directly — `freeWindows(bound, busy)` clips/merges busy
ranges and returns the gaps; `intersect(a, b)` merges two free-interval
lists into their overlap. Both are plain interval arithmetic with no I/O,
so they're unit-tested directly (`FreeWindowFinderTest`) without any
mocking — the kind of logic that's cheap to get exactly right in isolation
and expensive to debug once it's tangled into a service method.

**Search shape, per #023's decision**: one query for all candidate doctors'
booked appointments overlapping the business window that day, one for all
candidate rooms', grouped in memory into per-doctor/per-room `Interval`
lists — O(1) queries regardless of doctor/room count, not N queries or
blind time-stepping. For each (doctor, room) pair: the doctor's free
windows (their `DoctorSchedule` range for that day-of-week, intersected
with the business window, minus their bookings) intersected with the
room's free windows (business window minus its bookings) — first gap that
fits the requested/default duration becomes that pair's one candidate.

**Shared retry logic, factored out as real reuse, not speculation**: both
`book` (explicit time) and `bookOnDay` now build a `List<Candidate>`
(`doctor, room, startsAt, endsAt`) and hand it to a single
`tryBookCandidates` — the exact same
`DataIntegrityViolationException`/`RoomReservationFailedException`
handling and `MAX_BOOKING_ATTEMPTS` cap that already existed, just no
longer duplicated. The Stage 1 range-exclusion constraint is still the only
thing that actually prevents a race between concurrent requests — this
pre-computation is a candidate list, same as the explicit-time path's
doctor×room cartesian product; a lost race just falls through to the next
candidate exactly like a rejected reservation always has.

**New rule not covered by #023's original Q&A**: when `date` is *today* (in
the caller's `offset`), the effective window start is bumped from the fixed
9am to `max(9am, now rounded up to the next 15-minute grid line)` —
otherwise the search could hand back a start time already in the past.
Discovered as a necessary consequence while implementing, not a
speculative addition. **Not covered by a deterministic unit test** — doing
so properly would need injecting a `Clock` into `BookingService` (not
introduced, since nothing else in the class needs one), so this specific
edge is verified by live smoke test instead of `BookingServiceTest`;
documenting that gap explicitly rather than pretending it's covered.

**Still fails, does not auto-expand**, per #023: if nothing fits within
9am–6pm that day, `bookOnDay` throws `AppointmentAllocationException` (409)
even if the same doctor/room/day would work outside that window via an
explicit `startTime`.
