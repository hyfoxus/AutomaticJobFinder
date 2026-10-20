# JobApplier

Self-hosted tool that scans job sources, scores vacancies against your profile with an LLM, and emails a daily digest. Built as a small set of Spring Boot services in one monorepo.

> Stage 1 is in progress. The ingestion service and the scoring service exist so far.

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
│  ├─ scoring-service/            Pulls new vacancies, filters and scores them under a daily cost cap
│  │  ├─ docker/entrypoint.sh     Secret loader used inside the runtime image
│  │  ├─ Dockerfile               Multi-stage build (context = repo root)
│  │  └─ .env.sample              Template for service environment variables
│  └─ ingestion-service/          Fetches, stores and dedupes vacancies
│     ├─ src/                     Java sources, migrations and tests
│     │  └─ …/ingestion/          controller, service, repository, entity,
│     │                           dto, config, source (external integrations)
│     ├─ docker/entrypoint.sh     Secret loader used inside the runtime image
│     ├─ Dockerfile               Multi-stage build (context = repo root)
│     └─ .env.sample              Template for service environment variables
├─ config/                        Your profile.yml goes here (git-ignored, mounted read-only)
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

`scripts/setup.sh` reads `apps/ingestion-service/.env` (created from `.env.sample`). A plain `docker compose up` ignores it and uses the defaults, unless you pass `--env-file apps/ingestion-service/.env`.

The first fetch runs at the next six-hour mark, so `/api/jobs` is empty until then.

## Run the tests

```bash
./gradlew build      # needs Docker running (Testcontainers)
```

---

## Scoring service

Off by default (`scoring.enabled=false`): the container starts and reports healthy without a provider, key or profile.

To turn it on:

1. Copy `profile.sample.yml` to `config/profile.yml` and edit it. The directory is mounted read-only at `/app/config`.
2. In `apps/scoring-service/.env` set `SCORING_ENABLED=true`, `SCORING_AI_PROVIDER` (openai, anthropic or ollama), `SCORING_LLM_MODEL`, the provider key, and the model prices (see the comments in `.env.sample`).
3. `docker compose up -d --build`

What it does: on `SCORING_INGESTION_CRON` (default `0 15 */6 * * *`, `-` turns it off) it asks ingestion for jobs first seen since its cursor (minus a 5 minute overlap), scores them in chunks of 50 and moves the cursor after each chunk. It also retries rows that failed or were left unscored by the budget. Ingestion has no paging: one pull returns everything since the cursor, and the answer is capped at `scoring.ingestion.max-bytes` (64 MB). If ingestion is down the cursor stays and stored rows are still retried. A run stops when the provider rejects the key or the daily cap is reached.

Time zone: the cron schedule and the daily budget reset follow the container zone, which is UTC unless you set `TZ` (and optionally `SCORING_BUDGET_ZONE`) in `apps/scoring-service/.env`; both are shown commented in `.env.sample`.

Endpoints (on `127.0.0.1:${SCORING_HTTP_PORT:-8082}`, no authentication, so keep them on loopback):

```bash
curl "http://127.0.0.1:8082/api/assessments?status=scored&limit=20"   # newest first, limit 1..200
curl  http://127.0.0.1:8082/api/usage/today                           # spent, cap, state, calls (dollars as strings)
curl -X POST -H "X-JobApplier-Run: 1" http://127.0.0.1:8082/api/scoring/run   # 202, or 409 if a run is active
```

Assessment text (`reason`, gap texts) is written by the model: escape it wherever you display it. Job descriptions are never returned.

Network notes: scoring and ingestion are published on loopback only. Ingestion stays reachable from the other containers on `monorepo-net` at `http://ingestion-service:8081`. A new module must be added to both Dockerfiles' `COPY` lines (each copies only the other services' `build.gradle`).
