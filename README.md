# JobApplier

Self-hosted tool that scans job sources, scores vacancies against your profile with an LLM, and emails a daily digest. Built as a small set of Spring Boot services in one monorepo.

> Stage 1 is in progress. The ingestion, scoring and digest services exist so far.

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
│  ├─ digest-service/             Emails a digest of newly scored vacancies (off by default)
│  │  ├─ docker/entrypoint.sh     Secret loader used inside the runtime image
│  │  ├─ Dockerfile               Multi-stage build (context = repo root)
│  │  └─ .env.sample              Template for service environment variables
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

Network notes: scoring and ingestion are published on loopback only. Ingestion stays reachable from the other containers on `monorepo-net` at `http://ingestion-service:8081`. A new module must be added to the other Dockerfiles' `COPY` lines (each copies only the other services' `build.gradle`).

Scoring answers only to Host names on `scoring.api.allowed-hosts` (`SCORING_API_ALLOWED_HOSTS`, default `localhost,127.0.0.1`; compose adds `scoring-service` so the digest service can reach it). `GET /api/assessments` also takes `scoredSince` (ISO instant) and `afterId`: SCORED rows only, oldest scored first.

---

## Digest service

Off by default (`digest.enabled=false`): the container starts and reports healthy, with no digest beans. It reads newly SCORED assessments from the scoring REST API (never the scoring database), so scoring must be enabled too.

1. In `apps/digest-service/.env` set `DIGEST_ENABLED=true`. The default mode is `dry-run`: each run writes `digest-<UTC time>.html` and `.txt` into `./digest-out` and records nothing as sent, so every run shows the same jobs again.
2. To send mail set `DIGEST_MODE=smtp`, `DIGEST_SMTP_HOST`, `DIGEST_SMTP_FROM` and `DIGEST_SMTP_TO` (one or more addresses). The password goes into `secrets/digest/smtp_password` (git-ignored; only that folder is mounted, read-only) or `DIGEST_SMTP_PASSWORD`. STARTTLS is required unless the host is `localhost`, `127.0.0.1` or `::1`; `DIGEST_SMTP_SSL=true` uses implicit TLS; `DIGEST_SMTP_ALLOW_PLAINTEXT=true` is an explicit opt-out and logs a warning.
3. `docker compose up -d --build`

What it does: on `DIGEST_CRON` (default `0 0 8 * * *`, container time zone, `-` turns it off) it scans scored jobs from its cursor, keeps those in `DIGEST_TIERS` (default STRONG,PARTIAL) that were not mailed yet, and sends at most `DIGEST_MAX_ITEMS` (50): STRONG first, then by interview chance (a heuristic), then newest. Jobs that did not fit stay for the next run. No qualifying job, no mail. The sent jobs and the scan cursor are recorded in schema `digest` in one transaction after the mail server accepted the mail. Delivery is at-least-once: a crash between the two can repeat a mail but never loses a job. An SMTP or scoring failure records nothing and the next run retries.

Switching from dry-run to smtp: dry-run never records anything, so the first smtp run mails everything scored in the last 7 days (`digest.initial-lookback`) that qualifies, up to the limit per run.

All job and model text is untrusted and escaped; links are only for http/https urls; there are no remote images; the subject has counts only.

Health: `curl http://127.0.0.1:8083/actuator/health`. Reaching scoring from inside the container: `docker exec job-applier-digest curl -H "Host: scoring-service" http://scoring-service:8082/api/usage/today` (needs `curl` in the image; the digest startup log also reports whether scoring answers).
