# Make a money claim — HMCTS demo

**DEMO – this is not a real government service.** A synthetic civil-money-claims demonstration. No real court submissions, payments, emails or virus scans. Never enter real personal or card data.

GOV.UK Frontend 5.13 components, Arial/Helvetica, **no crown, Royal Coat of Arms or GDS Transport font**. Header: “HMCTS demo” / “Make a money claim”.

## Architecture

```mermaid
flowchart LR
  Person[Citizen / caseworker] --> UI[civil-citizen-ui<br/>Node 24 · Express 5 · Nunjucks]
  UI -->|000MC references| API[civil-case-api<br/>Java 21 · Spring Boot 3.5 · JPA]
  UI -->|000LC references| Legacy[legacy-claim-store<br/>Java 21 · JDBI 2.78 · SQL]
  API <-->|ClaimWorkflow / WorkflowCasePort| BPMN[Embedded Camunda 7.24<br/>Cockpit /camunda]
  API --> Civil[(PostgreSQL: civil<br/>claims · bytea documents · events)]
  BPMN --> Civil
  Legacy --> Old[(Same PostgreSQL: legacy_cmc<br/>JSON text claims · offers · documents)]
  UI & API & Legacy -. APM .-> Agent[Private Datadog agent]
  Agent --> DD[Datadog · env:demo]
  UI & API & Legacy -. JSON stdout .-> Logs[Platform / Compose log collector]
  Logs --> DD
```

The UI combines service lists and propagates request IDs/trace context. Each API owns its schema, Flyway history and domain logic. Camunda shares modern PostgreSQL transactions; legacy does not use Camunda.

## All-in-one deployment image (current target)

`Dockerfile.devindemos` packages PostgreSQL 16, both Java services, the Node UI and an nginx ingress.
Only **port 3000** is published. The ingress forwards the **unstripped** path; Express and Spring own their context paths.
Build stages compile production code only (no tests); the runtime contains a JRE, pruned Node dependencies and one shared Java tracer.

```sh
docker build -f Dockerfile.devindemos --build-arg GIT_SHA="$(git rev-parse HEAD)" -t cmc-demo .
docker run --name cmc-demo -p 3000:3000 \
  -e BASE_PATH=/f2ec3d90 -e DD_AGENT_HOST=your-private-agent-host \
  -e SESSION_SECRET -e POSTGRES_PASSWORD -e DEMO_RESET_TOKEN \
  -v cmc-demo-data:/var/lib/postgresql/data cmc-demo
curl --fail http://localhost:3000/f2ec3d90/health
TRAFFIC_BASE_URL=http://localhost:3000/f2ec3d90 TRAFFIC_ITERATIONS=3 node scripts/traffic.ts
```

Set the three secrets in your shell first (local-only defaults are provided if the flags are omitted).
Use `DD_TRACE_ENABLED=false` when no agent exists. For an HTTPS ingress, set `COOKIE_SECURE=true`; forwarded protocol headers are preserved.
The container starts the database, migrates/seeds both APIs, then starts the UI. Allow up to three minutes for initial readiness.
Tini reaps children; any application/database process exit stops the entire container. Use at least **2 GB RAM**.
The named volume persists PostgreSQL on restarts; omit it for disposable demo data. Do not run multiple containers against the same data directory.

| Entry point | Path beneath the deployment origin |
|---|---|
| Citizen UI / health | `/f2ec3d90/` / `/f2ec3d90/health` |
| Caseworker / operations | `/f2ec3d90/caseworker` / `/f2ec3d90/ops` |
| Modern API / OpenAPI | `/f2ec3d90/api/claims` / `/f2ec3d90/swagger-ui.html` |
| Legacy API / health | `/f2ec3d90/legacy/api/claims` / `/f2ec3d90/legacy/health` |
| Camunda Cockpit | `/f2ec3d90/camunda` |

`ARG GIT_SHA=unknown` sets `DD_VERSION` for all three services. APM goes to the **external** `DD_AGENT_HOST:8126`.
Application stdout is JSON with distinct `service`, `env`, `version`, request ID and trace/span IDs.
The platform must collect container stdout and parse/remap its JSON service field; `DD_AGENT_HOST` alone only transports APM, not logs.
No agent or API key is bundled in the image, and no Datadog monitors/Slack routing are created.
No public deployment is claimed here: this image is handed to the existing deployment platform.

## Local setup

Requirements: Docker + Compose. Host tests also need **Node 24, Java 21, Maven 3.6.3+**. No pre-commit hooks are configured.

```sh
make up                  # build/start all three apps and PostgreSQL
docker compose ps
curl --fail http://localhost:3000/health
curl --fail http://localhost:8080/health
curl --fail http://localhost:8081/health
make test                # UI + traffic + modern tests; legacy build-only
make reset               # restore seeds, evidence, sequences and workflows
make down                # stop stack; preserve the database volume
```

Wait for Java migrations/BPMN deployment before opening a flow. PostgreSQL is on host port **5433**, database/user/password `claims/claims/claims`. Override `POSTGRES_PASSWORD`, `SESSION_SECRET`, `DEMO_RESET_TOKEN` outside local use.

| Entry point | Local URL |
|---|---|
| Citizen UI | http://localhost:3000 |
| Make a claim | http://localhost:3000/make-claim/eligibility |
| Respond | http://localhost:3000/respond |
| Claimant dashboard | http://localhost:3000/dashboard |
| Caseworker | http://localhost:3000/caseworker |
| Operations / reset | http://localhost:3000/ops |
| Modern API / OpenAPI | http://localhost:8080/api/claims / http://localhost:8080/swagger-ui.html |
| Legacy API | http://localhost:8081/api/claims |
| Camunda Cockpit | http://localhost:8080/camunda |

Host development:

```sh
docker compose up -d postgres
(cd citizen-ui && npm ci && npm run dev)
mvn -B -s .mvn/settings.xml -f civil-case-api/pom.xml verify
mvn -B -s .mvn/settings.xml -f legacy-claim-store/pom.xml -DskipTests package
```

For host Java startup set `DATABASE_URL=postgresql://claims:claims@localhost:5433/claims`, `LOGGING_CONFIG=file:<repo>/observability/java/logback-spring.xml`. Legacy also needs `--add-opens=java.base/java.lang=ALL-UNNAMED` and `LEGACY_SEED_LOCATION=filesystem:<repo>/seed/legacy/migrations`; its Docker entrypoint supplies the Java 21 flag. Do not occupy Compose ports with duplicate host servers. See [CONTRACTS.md](CONTRACTS.md) and [legacy README](legacy-claim-store/README.md).

The checked-in public Google Maven Central mirror avoids observed Central rate limiting. All credentials in Compose are synthetic local defaults; never commit real secrets.

## Test logins and four flows

At **/sign-in**, select a role; **no password**:

| Role | Identity | Landing |
|---|---|---|
| Claimant | `demo-claimant` | /dashboard |
| Defendant | `demo-defendant` | /respond |
| Caseworker | `demo-caseworker` | /caseworker |
| Camunda admin (separate login) | `admin` / `admin` | /camunda |

1. **Make a claim:** claimant → eligibility → task list → parties → itemised amount → interest → reason → timeline → upload a fixture from `seed/evidence/` → fee/check answers + statement of truth → mock card payment → confirmation. Use the synthetic card values displayed on the payment page. Issue stores a valid claim-form PDF and starts a Camunda response timer.
2. **Respond:** defendant → reference/security code → view claim → admit/partly admit/reject → follow-up/payment proposal → check answers → confirmation. Modern responses persist history and cancel their timer. Legacy responses stay within legacy storage.
3. **Dashboard:** claimant → mixed modern/legacy list with status tags → details/history. Overdue `000MC002` and `000LC002` allow default-judgment requests; future-deadline claims do not.
4. **Caseworker:** caseworker → filter state/date and paginate → modern or legacy case → Summary, Parties, Claim, Documents, History → evidence download and case note. /ops shows both APIs' rates/latest errors and confirmation-protected reset.

The APIs and fake identity picker are **not production authentication**. Isolate access to an intended demo audience. UI sessions are bounded in-memory, lost on redeploy; use one UI instance.

### Seed references

| Example | Modern | Legacy | Code / deadline |
|---|---|---|---|
| Live response | `000MC001` | `000LC001` | `DEMO2026`, future deadline after reset |
| Default judgment | `000MC002` | `000LC002` | `MC000002` / `LC000002`, overdue unanswered |
| Easter | `000MC157` | `000LC037` | 7 April 2026 |
| Christmas | `000MC158` | `000LC038` | 29 December 2025 |
| Spring clock change | `000MC159` | `000LC039` | 30 March 2026 |
| Autumn clock change | `000MC160` | `000LC040` | 26 October 2026 |

160 modern + 40 legacy synthetic UK claims, £50–£25,000, across all states, owned by `demo-claimant`. Other codes are prefix + six digits. New references start at `000MC161` / `000LC041`. Live seed dates use today's London date; fixed historical examples are transferred, not running timers.

Reset deletes demo changes and restores 200 claims, evidence and sequences. Modern workflow deletion and seed SQL share a transaction; failed SQL preserves both. Workflow reseeding follows commit. The two APIs' resets are **not a distributed transaction**: stop traffic first, and rerun both after partial failure. /ops reports partial failures.

## Datadog and traffic

```sh
# Supply DD_API_KEY securely in your shell, never in a committed file.
export DD_SITE=datadoghq.com DD_ENV=demo
make up-observability
docker compose exec datadog-agent agent status
TRAFFIC_BASE_URL=http://localhost:3000 TRAFFIC_ITERATIONS=3 node scripts/traffic.ts
TRAFFIC_BASE_URL=http://localhost:3000 TRAFFIC_RATE_PER_MINUTE=60 \
  TRAFFIC_CONCURRENCY=8 TRAFFIC_DURATION_SECONDS=120 node scripts/traffic.ts
```

Plain `make up` needs no credentials (tracing off). Observed startup adds a **private** Datadog agent plus Docker JSON-log collection, with no published APM port. Services: **civil-citizen-ui**, **civil-case-api**, **legacy-claim-store**; `env:demo`, `version:<git SHA>`. dd-trace / dd-java-agent export APM. JSON logs contain request ID, trace/span IDs, timing/status and exception stacks, not bodies/security codes/files/card data. Error pages display a request reference.

Traffic uses real HTML/session/CSRF claim → upload → payment → response journeys. Rate means journeys/minute, not HTTP requests/minute. [Operations details](observability/README.md).

[Datadog logs](https://app.datadoghq.com/logs?query=env%3Ademo%20service%3A%28civil-citizen-ui%20OR%20civil-case-api%20OR%20legacy-claim-store%29) · [APM traces](https://app.datadoghq.com/apm/traces?query=env%3Ademo%20service%3A%28civil-citizen-ui%20OR%20civil-case-api%20OR%20legacy-claim-store%29)

No monitors or Slack routing are created.

## Archived Render setup (not the deployment target)

**The deployment target changed to `Dockerfile.devindemos`; do not create Render resources for this handoff.** No Render deployment was performed. The earlier Blueprint is retained below only as an alternative reference, not as evidence of deployment.

1. In [Render](https://dashboard.render.com), create a **Blueprint** from this repository, `render.yaml`, branch `main`. Review cost before creation. It defines three Docker web services, managed PostgreSQL 16 and a private Datadog agent in Frankfurt.
2. Supply `DD_API_KEY` for the agent when prompted. The Blueprint sets `DD_SITE=datadoghq.com`, `DD_ENV=demo`, service names and private agent routing. Application startup derives `DD_VERSION` from `RENDER_GIT_COMMIT`. APM has no public listener.
3. `fromDatabase` / `fromService` wire database and UI→API URLs; the UI accepts internal `host:port`. Session/reset secrets are generated; secure HTTPS cookies enabled. Override `CAMUNDA_ADMIN_USER` / `CAMUNDA_ADMIN_PASSWORD` before external sharing.
4. Configure a [Render HTTPS log stream](https://render.com/docs/datadog#stream-logs): endpoint `https://http-intake.logs.datadoghq.com/api/v2/logs`, token = Datadog API key. The private agent exports APM only; it cannot collect other services' stdout through a Docker socket. Parse the JSON message/remap its service and trace fields if Render's outer log envelope is retained.
5. Wait for all /health endpoints, exercise UI and /camunda, then run traffic against the Render UI. Confirm **logs and traces for all three services**. The Render transport is configured/documented, but **not verified on Render**.

The Blueprint validates against Render's official JSON Schema. An API key alone does not create APM transport. URLs are assigned by Render; none are invented. [Blueprint documentation](https://render.com/docs/blueprint-spec) · [Datadog integration](https://render.com/docs/datadog).

## Tests and quality

- `make test`: production UI build, **134 Vitest/Supertest tests**, **6 traffic fixture tests** and traffic typecheck, **145 JUnit tests** (including **40 PostgreSQL Testcontainers integration cases**, real Camunda and camunda-bpm-assert), **legacy build-only**.
- Base-path tests cover prefixed HTML/assets/forms, health, redirects, upload JSON, cookie scope, sign-in/sign-out and traffic-driver redirects.
- `IntegratedWorkflowIT`: real REST/domain + seed + BPMN delegates + PDF storage + response cancellation + repeated reset, with a failed-reset rollback regression across business and workflow tables.
- `./scripts/test-seed.sh`: isolated disposable PostgreSQL database, migrations and repeated seed reset assertions.
- `./scripts/test-java-logging.sh`: real encoder, correlation fields, HTTP timing and exception stack.
- Bounded `node scripts/traffic.ts`: live HTML flow 1/2 with evidence against the full stack.
- [GitHub Actions](https://github.com/COG-GTM/civil-money-claims-demo/actions/workflows/ci.yml): UI/traffic, modern unit/integration, legacy build-only and Compose validation on every PR/main push.

Legacy intentionally has **no unit tests**, but must work correctly. All four browser flows must be verified against real storage, not just mocked route tests. Upstream GOV.UK Sass emits a non-blocking `@import` deprecation warning.

## Domain logic map

Java paths are relative to `civil-case-api/src/main/java/uk/gov/hmcts/demo/civil/` (modern) or `legacy-claim-store/src/main/java/uk/gov/hmcts/demo/legacy/` (legacy), unless noted.

| Rule | File | Service |
|---|---|---|
| Fees £35/50/70/80/115/205/455, then 5% floored to pennies, £10k cap | `domain/FeeCalculator.java` | civil-case-api |
| 8% simple interest, calendar days /365, daily rate and rounding | `domain/InterestCalculator.java` | civil-case-api |
| Court fee applies to principal plus accrued interest | `service/FeeQuoteService.java` | civil-case-api |
| Service = issue +5 days; response +14; acknowledgement +28; final-date weekend/E&W holiday rollover | `domain/DeadlineCalculator.java`, `persistence/BankHolidayRepository.java` | civil-case-api |
| Unique sequential 000MC references | `domain/ClaimReferenceGenerator.java` | civil-case-api |
| Allowed states and default-judgment guards | `domain/ClaimStateMachine.java`, `service/ClaimService.java` | civil-case-api |
| Draft/issue/response validation, codes, acknowledgement, notes/history | `service/ClaimService.java`, `web/ClaimModels.java` | civil-case-api |
| PDF/JPEG/PNG magic/type/10 MiB validation; CLEAN scan stub | `document/EvidenceValidator.java`, `document/DemoVirusScanner.java` | civil-case-api |
| Evidence bytea storage and safe downloads | `document/DocumentStore.java`, `document/PostgresDocumentStore.java`, `document/DocumentService.java` | civil-case-api |
| Idempotent, valid claim-form PDF | `document/ClaimFormGenerator.java`, `service/ClaimService.java` | civil-case-api |
| Issue → PDF → notification stub → timer; response cancels; expiry marks awaiting judgment | `src/main/resources/camunda/money-claim.bpmn` (service root), `camunda/*Delegate.java` | civil-case-api |
| Durable workflow deduplication, acknowledgement reschedule, London next-midnight timer across DST | `camunda/CamundaClaimWorkflow.java`, `camunda/ClaimProcess.java` | civil-case-api |
| Atomic business/workflow reset; post-commit reseed | `service/DemoResetService.java`, `camunda/CamundaClaimWorkflow.java` | civil-case-api |
| Legacy Date/Calendar deadlines, acknowledgement, holiday rollover | `service/LegacyResponseDeadlineCalculator.java` | legacy-claim-store |
| Legacy fee/interest, drafts/issue/response/judgment and state validation | `service/ClaimService.java` | legacy-claim-store |
| Legacy JSON-text persistence, events, notes, evidence and reset | `service/ClaimService.java`, `repository/ClaimRepository.java` | legacy-claim-store |
| Legacy settlement offer creation/acceptance/rejection | `service/ClaimService.java`, `repository/OffersRepository.java` | legacy-claim-store |
| Unique sequential 000LC references, handwritten SQL | `repository/ReferenceNumberRepository.java` | legacy-claim-store |
| Form/task validation, mock payment, CSRF/session boundaries and reference routing | `citizen-ui/src/features/`, `citizen-ui/src/app.ts`, `citizen-ui/src/lib/api-client.ts` (repo root) | civil-citizen-ui |
| Deterministic 160/40 seed and holiday snapshot | `scripts/generate-seed.py`, `seed/bank-holidays.json`, `seed/*/reset.sql` (repo root) | both APIs |

The acknowledgement rule is an **explicit demo simplification**, not general CPR advice. “Skipping weekends” rolls the final deadline, not 14 working days. Holiday snapshot covers **2019–2028**; add a migration with updated official dates before relying on later years. Never rewrite applied migrations.

## Research and attribution

Public [GOV.UK claims](https://www.gov.uk/make-court-claim-for-money), [fees](https://www.gov.uk/make-court-claim-for-money/court-fees), [interest](https://www.gov.uk/make-court-claim-for-money/interest), [holidays](https://www.gov.uk/bank-holidays.json) and [Design System](https://design-system.service.gov.uk) informed wording/rules. MIT-licensed HMCTS [civil-citizen-ui](https://github.com/hmcts/civil-citizen-ui), [civil-service](https://github.com/hmcts/civil-service), [cmc-claim-store](https://github.com/hmcts/cmc-claim-store), [cmc-citizen-frontend](https://github.com/hmcts/cmc-citizen-frontend) informed structure. See [CONTRACTS.md](CONTRACTS.md). No real government accounts/submissions, copied logos or deliberate bugs.
