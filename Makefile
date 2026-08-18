.PHONY: help up down build build-and-deploy-appointments logs clean

COMPOSE ?= docker compose
MVNW ?= ./mvnw

help:
	@echo "Targets:"
	@echo "  build-and-deploy-appointments - up docker-compose infra, build the app, run it seeded (foreground; Ctrl+C to stop)"
	@echo "  up                            - start docker-compose infra only (Postgres, Kafka, WireMock, GreenMail, Grafana LGTM)"
	@echo "  down                           - stop docker-compose infra"
	@echo "  build                          - compile/package the app (tests skipped)"
	@echo "  logs                           - tail docker-compose infra logs"
	@echo "  clean                          - stop infra and remove build artifacts"

up:
	$(COMPOSE) up -d
	@echo "Waiting for Postgres to accept connections..."
	@until docker exec appointments-database pg_isready -U myuser -d mydatabase > /dev/null 2>&1; do sleep 1; done
	@echo "Infra is up."

down:
	$(COMPOSE) down

build:
	$(MVNW) clean package -DskipTests

# Runs via spring-boot:run (not the packaged jar) to match the dev workflow
# already documented in README - live-reload friendly, recompiles on the fly.
# `build` still runs first so a compile error fails fast, before infra's spun
# up for nothing.
build-and-deploy-appointments: build up
	$(MVNW) spring-boot:run -Dspring-boot.run.profiles=seed

logs:
	$(COMPOSE) logs -f

clean: down
	$(MVNW) clean
