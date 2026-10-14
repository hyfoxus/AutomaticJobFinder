# JobApplier

Self-hosted tool that scans job sources, scores vacancies against your profile with an LLM, and emails a daily digest. Built as a small set of Spring Boot services in one monorepo.

> Stage 1 is in progress. Only the ingestion service exists so far.

---

## Prerequisites

- **Docker** 24+ and **Docker Compose** v2
- Optional (local JVM run): **Java 25**; the Gradle wrapper is included

---

## Repository Layout

```
JobApplier/                       Monorepo root
├─ apps/
│  ├─ contracts/                  Shared DTOs, no logic
│  └─ ingestion-service/          Fetches, stores and dedupes vacancies
│     ├─ src/                     Java sources, migrations and tests
│     │  └─ …/ingestion/          controller, service, repository, entity,
│     │                           dto, config, source (external integrations)
│     ├─ docker/entrypoint.sh     Secret loader used inside the runtime image
│     ├─ Dockerfile               Multi-stage build (context = repo root)
│     └─ .env.sample              Template for service environment variables
├─ docker-compose.yml             Local stack (PostgreSQL + services)
├─ scripts/setup.sh               Creates .env files and starts the stack
├─ build.gradle, settings.gradle  Aggregator build
└─ README.md                      This guide
```

---

## Quick Start

```bash
scripts/setup.sh --build
curl http://localhost:8081/actuator/health
curl "http://localhost:8081/api/jobs?since=1970-01-01T00:00:00Z"
```

The first fetch runs at the next six-hour mark, so `/api/jobs` is empty until then.

## Run the tests

```bash
./gradlew build      # needs Docker running (Testcontainers)
```
