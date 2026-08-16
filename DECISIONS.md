# Decisions & Assumptions Log

Running log of choices made building this take-home, kept honest and in order
so I can walk a reviewer through the "why" behind anything in the diff — not
just the "what." Entries added as the build progresses.

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

---

*More entries land here as domain modeling, API design, and testing choices
get made.*
