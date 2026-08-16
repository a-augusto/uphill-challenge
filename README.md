# Uphill Challenge — Appointments Service

Take-home solution for Uphill Health's Senior Developer challenge.

> Requirements write-up pending — this README grows alongside the build.

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
| Boilerplate | Lombok |

## Running locally

```bash
./mvnw spring-boot:run
```

`docker-compose.yaml` provisions the local Postgres instance; Spring Boot's
Docker Compose integration starts it automatically on run.

## Decisions & assumptions

Every non-obvious call made during this build — tech choices, trade-offs,
things assumed in the absence of a spec answer — is logged in
[`DECISIONS.md`](./DECISIONS.md), in the order made. Read that alongside this
README for the full picture of *why* the code looks the way it does.

