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
│  ├─ web-ui/                     Minimal local web UI over the scoring API (off by default, Thymeleaf, no JS)
│  │  ├─ docker/entrypoint.sh     Starts the jar
│  │  ├─ Dockerfile               Multi-stage build (context = repo root)
│  │  └─ .env.sample              Template for service environment variables
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

Scoring answers only to Host names on `scoring.api.allowed-hosts` (`SCORING_API_ALLOWED_HOSTS`, default `localhost,127.0.0.1`; compose adds `scoring-service` so the digest service can reach it). `GET /api/assessments` also takes `scoredSince` (ISO instant) and `afterId`: SCORED rows only, oldest scored first. For the web UI it takes `tier` (STRONG, PARTIAL or WEAK; only with status SCORED or none) and `page` (0 or more; offset is `page * limit`, within the first 10000 rows, else 400), and answers with an `X-Has-More: true|false` header. Offset paging shifts when new rows arrive between two requests.

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

---

## Web UI

Off by default (`ui.enabled=false`): the container starts and reports healthy, with no pages. It needs scoring enabled, because every page is built from the scoring REST API; it has no database and no JavaScript.

1. In `apps/web-ui/.env` set `UI_ENABLED=true`.
2. `docker compose up -d --build`, then open `http://127.0.0.1:8084/` (`UI_HTTP_PORT` changes the port; it is published on loopback only).

Pages: `/` shows today's spend against the cap, counts of the most recent 200 rows and a button that starts a scoring run (at most one accepted per minute; the daily cost cap in scoring still applies). `/assessments?status=&tier=&page=` lists rows, 50 per page, newest first; status defaults to SCORED, other values are whitelisted and anything else is 400. The interview chance is a heuristic. Offset paging can shift when new rows arrive.

There is no login, so keep it on loopback. It answers only to the Host names in `UI_ALLOWED_HOSTS` (default `localhost,127.0.0.1`), sets a strict Content-Security-Policy and the other security headers on every response, shows job and model text as plain text only, and links a posting only when its url is plain http or https. The run button is the only write: it needs a CSRF token (cookie plus form field) and an Origin equal to the Host. If scoring is down the pages show a fixed notice, never the error text.

Health: `curl http://127.0.0.1:8084/actuator/health`. Digest status is not shown yet.

---

## Recipe learner

Off by default (`INGESTION_LEARNER_ENABLED=false`): no learner code runs and `/api/learn` does not exist. When on, it asks the model for a recipe for one careers site, checks it, tries it on a small live sample and saves it **disabled** in `./recipes-proposed` (writable for ingestion only; never loaded, never inside `./recipes`). The model call goes through scoring (`POST /api/llm/complete`) and counts against the same daily cost cap, plus its own limits (3 learn runs and $0.25 a day by default, see `apps/scoring-service/.env.sample`).

Adding a host, after you have reviewed its terms of use and robots.txt:

1. Read the site's terms of use and its `robots.txt`. Only continue if automated reading of the job pages is allowed. The learner never bypasses robots.txt, bot challenges or a refusal.
2. Run `scripts/setup.sh` once (or create `secrets/learner/token` yourself, at least 16 characters). Ingestion and scoring both mount this file, and it must exist before `docker compose up`: if it is missing Docker creates a directory of that name, which `scripts/setup.sh` detects and refuses (remove the directory and run it again). Scoring has no learn endpoint without the file. Scoring must be enabled with a provider.
3. In the **root** `.env` (next to `docker-compose.yml`; compose reads these variables from there, `apps/ingestion-service/.env` does not override them) set `INGESTION_LEARNER_ENABLED=true` and add the exact host name to `INGESTION_LEARNER_ALLOWED_HOSTS` (comma separated, no scheme, no subdomain wildcard; listing a host is your record that you reviewed it). Then `docker compose up -d --build`.
4. Start a run (one at a time, loopback only; the answer is 202 with a run id, 409 if busy, 400 if the host is not listed):

   ```bash
   curl -X POST -H "X-JobApplier-Run: 1" -H "Content-Type: application/json" \
        -d '{"url": "https://careers.example.com/jobs"}' http://127.0.0.1:8081/api/learn
   ```

5. Read `recipes-proposed/<name>.report.txt` (outcome, strategy, sample titles, notes). On success `<name>.json` sits next to it with `"enabled": false`. Existing files are never overwritten; a clash adds a numeric suffix.
   Sitemap recipes may set `"keepQuery": true` in the `sitemap` block when the job id lives in the URL query (for example `...?id=R123`); the default drops the query, as before.
6. Review the recipe, move it to `./recipes`, set `"enabled": true`, and restart ingestion (recipes load once at startup).

Outcomes you may see in the report: no fitting strategy (the site needs a JSON API or JavaScript that none of the three strategies cover), blocked by robots.txt or a bot challenge, budget reached, model unavailable, or no valid recipe after two model calls. A timeout or a server error is never retried. Page text sent to the model is a reduced, capped skeleton and is treated as data; the reply is parsed only into the recipe schema and every URL in it must be on the host you started from.

### Cloning a recipe to every country

A sitemap recipe covers one locale. Once one recipe works (for example `recipes/accenture-ie-en.json`, the template), `POST /api/clone` copies it to every sibling locale in the site's sitemap index, sample-tests each copy and saves the passing ones **disabled** in `./recipes-proposed`. No model call and no scoring cost. It needs the learner switched on, the host in `INGESTION_LEARNER_ALLOWED_HOSTS` and the template loaded (in `./recipes`, enabled or not).

```bash
curl -X POST -H "X-JobApplier-Run: 1" -H "Content-Type: application/json" \
     -d '{"template": "accenture-ie-en", "indexUrl": "https://www.accenture.com/sitemap-index.xml"}' \
     http://127.0.0.1:8081/api/clone
```

The answer is 202 (409 if a learn or clone run is active, 400 for an unknown or non-locale-specific template or an index on another host). Siblings are index entries whose URL equals the template's sitemap URL with only the first path segment changed. Each clone is named after the template with its locale replaced (`accenture-ie-en` gives `accenture-de-de`). Expect about 6 requests per locale at the site's pacing (plus about 6 more once for a sample of the template locale), so roughly 15 minutes for 54 locales; limits: 80 locales and 30 minutes per run (`INGESTION_LEARNER_MAX_CLONES`, up to 120, and `INGESTION_LEARNER_CLONE_MAX_RUN`), 2 clone runs a day (a third answers 400).

Read `recipes-proposed/<template>-clone.report.txt`: it lists every index entry as COVERED (sample passed, file written), FAILED (with the reason) or SKIPPED (not a sibling, already proposed, cap, time budget), and the counts add up to the index size. A locale passes when its sitemap has at least one matching URL and at least min(3, matched) sampled pages give a title and URL. Existing files are never overwritten, so a rerun resumes (already proposed entries are skipped). Then review the files you want, move them to `./recipes`, set `"enabled": true` and restart ingestion. Things to check first: the same job may appear in several locales under different ids and be scored twice (the report shows how many sample ids a locale shares with the template), and many enabled recipes mean many more detail fetches per cron run (the 100-per-recipe limit spreads them over several runs). `recipes/accenture-ie-en.json` stays untracked until you decide to commit it.

#### Enabling the profile's countries automatically

Off by default (`INGESTION_LEARNER_AUTO_ENABLE=false`, a separate switch from the learner). When on, a clone run also enables the passing clones whose country is listed in the `locationCountries` field of `config/profile.yml` (see `profile.sample.yml`): two-letter codes, `uk` for `gb`, and the shortcuts `europe` (EU27 plus gb, ch, no, is, li, ua, rs, ba, me, mk, al, md) and `eu` (EU27). The country is the first part of the locale (`ie-en` gives `ie`), so this relies on Accenture's country-language naming. Ingestion reads only that one field of the profile (mounted read-only; every other field is ignored and never logged), and an empty list, an unreadable file or an unknown entry enables nothing and the report says why.

- Enabled recipes are written, enabled, to `./recipes-auto` (writable for ingestion only; never to `./recipes`) and are loaded at startup, also when auto-enable is off. **Restart ingestion after a clone run** to make them active; the report says so.
- Only clones that passed their sample in that run, whose host is in `INGESTION_LEARNER_ALLOWED_HOSTS`, from a recipe that is already loaded. Never from a model proposal. At most one locale per country (`-en` preferred). A listed country whose proposal already exists is tested again and enabled from the template.
- At most `INGESTION_LEARNER_MAX_AUTO_ENABLED` files in `recipes-auto` (default 20, limit 60), counting files already there. Extra matches are marked `cap`.
- The report marks each entry `ENABLED`, `not in profile`, `cap`, `blocked`, `already enabled`, `name taken`, `country unknown` or `same country already enabled`, with a summary.
- To switch a country off, delete its file from `recipes-auto` **and** list its name in `INGESTION_LEARNER_AUTO_ENABLE_BLOCKED` (comma separated, e.g. `accenture-de-de`); otherwise the next clone run enables it again. Restart ingestion afterwards.
- A listed country must also pass `filters.allowedLocationKeywords`, because scoring ignores `locationCountries`. Many enabled recipes mean many detail fetches per cron run; ingestion warns at startup when enabled sources times the run limit exceed the cron interval.
- Auto-enable only does something when the learner is on too (`INGESTION_LEARNER_ENABLED=true`). The `./config` folder is mounted read-only into ingestion, as for scoring; the profile is read from `config/profile.yml` inside it.
- On Linux, files written to `./recipes-auto` by the container are owned by root (the container user); delete them with sudo or change the owner.

