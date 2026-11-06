# Stage A integration contracts

This is a synthetic HMCTS demonstration, not a government service or legal advice. No real payments, letters, scans or personal data. This document and `contracts/*.json` are the frozen Stage B interfaces. Stage C may reconcile them after integration; Stage B must not edit shared files.

## Ownership (non-overlapping)

| Agent | Owned paths | Notes |
|---|---|---|
| B1 domain / REST / evidence | `civil-case-api/`, excluding the B2/B7 paths below | Owns V100–V199 core migrations, documents and schema, domain, REST, integration tests. Do not edit `pom.xml`, Dockerfile or Stage A bridge interfaces. |
| B2 Camunda | `civil-case-api/src/main/java/uk/gov/hmcts/demo/civil/camunda/`, `civil-case-api/src/main/resources/camunda/`, `civil-case-api/src/main/resources/db/migration/camunda/`, `civil-case-api/src/test/java/uk/gov/hmcts/demo/civil/camunda/` | V200–V299; only workflow-owned tables here, not document tables owned by B1. All dependencies pre-added. |
| B3 legacy | `legacy-claim-store/` | Owns all legacy Java and core migrations; no tests by design. Root `seed/legacy/` and `observability/` are outside this ownership. Do not edit frozen build/Docker files. |
| B4 make a claim | `citizen-ui/src/features/make-claim/` | Routes, views, tests, assets beneath this folder. |
| B5 response + dashboard | `citizen-ui/src/features/respond/`, `citizen-ui/src/features/dashboard/` | Includes default judgment request. |
| B6 staff + ops + sign-in | `citizen-ui/src/features/caseworker/`, `citizen-ui/src/features/ops/`, `citizen-ui/src/features/sign-in/` | `/sign-in`, `/sign-out`, `/caseworker`, `/ops`. |
| B7 observability + seed + traffic | `observability/`, `citizen-ui/src/observability/`, `civil-case-api/src/main/resources/db/migration/seed/`, `seed/`, `scripts/` | V300–V399 modern data; legacy seed uses V300–V399 in `seed/legacy/migrations/`. Owns reset SQL and scripts; B1/B3 own their reset endpoint implementations. |

Frozen shared files: root config/README/CONTRACTS, `contracts/`, both Java `pom.xml`/Dockerfiles, UI package files/config, `citizen-ui/src/{app.ts,server.ts,route-loader.ts,lib/,types/,views/,assets/}`, `civil-case-api/.../workflow/`. No Stage B agent edits another owner's paths. If a shared change is necessary, report it for Stage C rather than racing another agent.

## Runtime and dependencies

Node **24**, TypeScript, Express **5**, Nunjucks, govuk-frontend **5.13**. Java **21**, **Maven** (not Gradle), Spring Boot **3.5.7** in both services. Modern: JPA, Flyway PostgreSQL, embedded Camunda **7.24.0**, springdoc, PDFBox, JUnit 5, camunda-bpm-assert, Testcontainers. Legacy: **org.jdbi:jdbi:2.78**, `org.skife.jdbi.v2` SQL-object DAOs, JDBC, a large ClaimService and Date/Calendar deadlines. No deliberate bugs and no legacy tests. Dependencies for all Stage B agents already present; npm lockfile is shared/frozen.

Build from repo root with `mvn -B -s .mvn/settings.xml -f civil-case-api/pom.xml verify` (or legacy `-DskipTests package`). The public Google Maven Central mirror avoids observed HTTP 429 responses from repo.maven.apache.org in this environment; Makefile, CI and Docker already use it. Vitest 4.1.12 and current patched Multer/dd-trace/Zod selected after npm audit; do not downgrade.

Ports: UI **3000**, modern **8080**, legacy **8081**, Postgres **5432** (local host **5433** to avoid conflicts). Services expose `/health`. API prefix `/api`; Camunda Cockpit `/camunda`, demo-only `admin/admin`. `make up` builds/starts all; `make reset` invokes both reset endpoints; `make test` runs UI + modern tests and legacy build-only. All Docker build contexts are repository root.

### Environment

| Variable | Meaning / local default |
|---|---|
| `PORT` | Per-service HTTP port; Render supplies this |
| `CIVIL_CASE_API_URL` | UI→modern, `http://localhost:8080`; Compose uses `http://civil-case-api:8080` |
| `LEGACY_CLAIM_STORE_URL` | UI→legacy, `http://localhost:8081`; Compose uses `http://legacy-claim-store:8081` |
| `DATABASE_URL` | PostgreSQL URL `postgresql://claims:claims@localhost:5433/claims`; JDBC URLs also accepted with `DATABASE_USERNAME` / `DATABASE_PASSWORD` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Explicit JDBC credentials; URL credentials otherwise parsed at startup |
| `SESSION_SECRET` | Random secret in production; local-only default provided by Compose |
| `COOKIE_SECURE` | `true` on Render HTTPS, `false` locally; UI trusts one reverse proxy |
| `DD_API_KEY`, `DD_SITE` | Datadog ingest config; never commit real values; default site `datadoghq.com` |
| `DD_ENV`, `DD_VERSION`, `DD_SERVICE` | `demo`, git SHA, `civil-citizen-ui` / `civil-case-api` / `legacy-claim-store` |
| `DD_TRACE_ENABLED`, `DD_AGENT_HOST`, `DD_TRACE_AGENT_URL` | Instrumentation off by default locally; B7 provides transport configuration. An API key alone does **not** send APM without an agent/collector. |
| `DD_LOGS_INJECTION` | `true`; B7 owns JSON config and export wiring, no monitors/routing |
| `DEMO_RESET_ENABLED`, `DEMO_RESET_TOKEN` | Explicitly enable destructive demo-only reset; UI/server-side script sends `X-Demo-Reset-Token`; never put token in browser JS |
| `DEMO_RESET_SQL` | Modern `/app/seed/modern/reset.sql`, legacy `/app/seed/legacy/reset.sql`; B7 supplies idempotent SQL |
| `LEGACY_SEED_LOCATION` | `filesystem:/app/seed/legacy/migrations`; Maven/local may use `filesystem:../seed/legacy/migrations` |
| `CAMUNDA_ENABLED` | Defaults `true`; unit tests may turn it off |
| `TRAFFIC_BASE_URL`, `TRAFFIC_RATE_PER_MINUTE` | B7 traffic driver settings |

## HTTP and JSON contract

Canonical OpenAPI 3.0.3: `contracts/civil-case-api.openapi.json` and `contracts/legacy-claim-store.openapi.json`. They are contracts to implement, not claims that the skeleton implements endpoints yet. Both expose the same Claim shape so UI routing is transparent. UI calls **legacy directly for `000LC` references**, modern for `000MC`; dashboard/caseworker merge lists from both APIs.

`X-Request-ID` is generated at the UI boundary (UUID), validated to safe ASCII, echoed in response and forwarded to downstream APIs. Errors: `{status, code, message, requestId, fieldErrors?: {field: message}}`. 400 invalid data, 403 bad lookup/reset token, 404 missing resource, 409 illegal state/version, 413 oversized evidence, 415 unsupported media type, 500 unhandled failure. Never expose stack traces in HTTP; log them. UI error wording exactly: **Sorry, there is a problem with the service. Reference: <request-id>**. Log JSON fields: `timestamp`, `level`, `service`, `env`, `version`, `message`, `request_id`, `dd.trace_id`, `dd.span_id`; request events also `method`, `path`, `status`, `duration_ms`; exceptions `error.kind`, `error.message`, `error.stack`. Never log security codes, card inputs, tokens, document bodies or party payloads.

Money is JSON number in **GBP pounds**, max 2 decimal places, mapped to Java BigDecimal / PostgreSQL numeric(14,2), never binary floating arithmetic for totals. `interest.rate` is **8**, not 0.08. Daily interest may have 6 decimal places. Dates ISO `YYYY-MM-DD`, instants ISO UTC; court calendar uses `Europe/London`, not elapsed 24-hour milliseconds. All list responses `{items, total, page, size}`, zero-based page; events/notes/documents are JSON arrays. Query filter names `state`, `from`, `to`, `claimantId`, `page`, `size`.

No genuine identity provider: session `user = {id, role, name}`; roles `claimant`, `defendant`, `caseworker`; claimant ID **demo-claimant**, defendant **demo-defendant**, staff **demo-caseworker**. `X-Demo-User-Id` and `X-Demo-Role` may be forwarded. These are not production authentication. Only synthetic data; explicitly label sign-in and card screens as demos. API responses may include demo securityCode for staff/claimant views; UI must not display it in pre-lookup defendant pages.

### Claims and state

References sequence **000MC001**, 000MC002… (minimum 3 decimal digits, expand beyond 999); legacy **000LC001**…; regex `^000(MC|LC)[0-9]{3,}$`. Never use UUID as display reference; internal `id` is UUID. Concurrent reference generation must use database sequences. 8-character uppercase alphanumeric security code (`^[A-Z0-9]{8}$`), generated securely; deterministic demo seed code **DEMO2026**. Normalize typed refs/codes with trim + uppercase.

States: `DRAFT → ISSUED → AWAITING_RESPONSE → RESPONDED | AWAITING_DEFAULT_JUDGMENT`; `RESPONDED → SETTLED | TRANSFERRED`; `AWAITING_DEFAULT_JUDGMENT → SETTLED | TRANSFERRED`; allow late response from AWAITING_DEFAULT_JUDGMENT only before judgmentRequestedAt is set. Issue endpoint commits ISSUED, records events, then workflow transitions to AWAITING_RESPONSE. Acknowledge updates deadline, not state. A default-judgment request sets `judgmentRequestedAt` and adds event, keeping AWAITING_DEFAULT_JUDGMENT (do not invent an uncontracted JUDGMENT state). Eligibility requires no response, no previous judgment request, and today's London date **after** responseDeadline. Full/part/reject responses use `ADMIT_ALL`, `ADMIT_PART`, `REJECT`; accepting liability is not itself payment, so all become RESPONDED, not SETTLED.

### Fee, interest and deadlines

Verified 6 Nov 2026 from https://www.gov.uk/make-court-claim-for-money/fees. Current online and paper **same** issue fee; do not use historic online discounts. Fee base includes interest. Brackets inclusive upper bound:

| Claim including interest | Online | Paper |
|---|---:|---:|
| ≤ £300 | £35 | £35 |
| £300.01–£500 | £50 | £50 |
| £500.01–£1,000 | £70 | £70 |
| £1,000.01–£1,500 | £80 | £80 |
| £1,500.01–£3,000 | £115 | £115 |
| £3,000.01–£5,000 | £205 | £205 |
| £5,000.01–£10,000 | £455 | £455 |
| £10,000.01–£200,000 | 5%, round **down** to penny | same |
| > £200,000 | £10,000 | £10,000 |

Demo UI limit £25,000 principal, but calculator supports full fee table. Unknown value/paper claim is out of scope. Interest https://www.gov.uk/make-court-claim-for-money/interest: 8% simple annual, daily = principal × 0.08 / 365, no compounding; days = calendar days between startDate and calculation date, clamp invalid future dates with validation rather than negative interest. Retain precision until total rounded HALF_UP to pennies; display daily rate to pennies, API keeps 6 decimals. Fee percentage specifically floors, unlike interest.

Demo serviceDate = issue date + **5 calendar days** (explicit simplified service assumption). Standard deadline = serviceDate + **14 calendar days**, then roll forward to next weekday not in England/Wales holidays. As requested in the brief, acknowledgement adds **28 additional calendar days**, calculated from original serviceDate + 14 + 28, then rolled forward (not from already-rolled deadline). This is an intentional demo rule, **not a statement of general CPR acknowledgment rules**. “Skipping weekends” means roll the resulting deadline, not count 14 working days. Legacy uses Date/Calendar but must produce identical dates across DST. Holidays checked in from https://www.gov.uk/bank-holidays.json (England/Wales only); no live network needed at runtime. B7 persists rows for supported years and seed edge cases; B1 owns calculator.

## Data schema / migrations

One Postgres database, two schemas. Modern `civil`; legacy `legacy_cmc`. Each has independent Flyway history table. Modern locations `classpath:db/migration/core` (V100–199), `classpath:db/migration/camunda` (V200–299), `classpath:db/migration/seed` (V300–399). Legacy `classpath:db/migration/legacy` (V1–99) plus `LEGACY_SEED_LOCATION` (V300–399). Never reuse a version. `out-of-order=true` supports merging independent owners during the demo, but do not change already-applied migrations.

Stage A supplies structural migrations to freeze seed interfaces (B1/B3 may add migrations, coordinate breaking schema changes via Stage C). Modern `claims`: UUID id, reference unique, claimant_id, state, claimant/defendant JSONB, amount_breakdown JSONB, principal_amount, interest JSONB, interest_amount, daily_interest, fee, total_amount, reason, timeline JSONB, security_code, acknowledged, created_at/updated_at, issued_at, service_date, response_deadline, response JSONB, judgment_requested_at, version. `claim_events` (id,claim_id,type,description,actor,created_at), `case_notes` (id,claim_id,text,author,created_at), `documents` (id,claim_id,file_name,content_type,size_bytes,content BYTEA,scan_status,created_at), `bank_holidays` (date,title). Modern `claim_reference_seq` starts 161 after 160 seeded claims. Legacy `claims` stores same external model as **claim_data TEXT** plus indexed scalar ref/state/deadline/claimant fields; `offers`, `claim_events`, `case_notes`, `documents` are owned by legacy and independent. Its reference sequence starts 41 after 40 seeded claims.

Camunda engine manages ACT_* tables in **civil**; no competing Flyway scripts for those tables. B2 uses deployment path `classpath:camunda/*.bpmn`. Business key = reference. Process key **money-claim**, start message **CLAIM_ISSUED**, response message **DEFENDANT_RESPONSE**. Variables `claimReference`, `responseDeadline` (ISO date), `responseDeadlineAt` (ISO offset datetime next day 00:00 Europe/London). Timer must not expire at start of final response date. Delegates call the frozen **WorkflowCasePort** rather than repositories. B1 implements it; B2 implements **ClaimWorkflow**. Both interfaces are in `uk.gov.hmcts.demo.civil.workflow`; see Java docs for methods. B1 issue/response/acknowledge/reset calls this bridge; B2 handles idempotency and timer updates. No custom Camunda HTTP endpoints needed.

Evidence: multipart field **file**, optional **description**, PDF/JPEG/PNG only, **10 MiB** maximum; validate magic bytes and type, not just extension. B1 owns DocumentStore and bytea storage, safe download filenames, scan stub `CLEAN`; B2 calls `generateClaimForm(reference)` through bridge, never writes documents SQL. Stub PDF must be a valid PDF. Reset endpoints call workflow reset first (modern), then execute B7's SQL transaction; return `{status:"reset",claims:160}` / 40. Reset SQL never drops schemas/Flyway/ACT tables; it resets owned business tables and sequences. B2 starts missing seed workflows on application ready and exposes reseed via bridge. B1 calls `workflow.seedActiveClaims()` after reset commit. Reset disabled unless explicitly configured and token checked.

## UI extension interface

Feature folders export named `registerRoutes(app: Express): void` from **routes.ts**. Loader finds every immediate `src/features/*/routes.ts` (compiled `dist/features/*/routes.js`) sorted by name. No central route edits. Each feature template is `src/features/<feature>/views/<feature>/page.njk` and renders `<feature>/page.njk`; all `views` roots auto-registered. Assets at `<feature>/assets/` appear under `/assets/features/<feature>/`. Tests `*.test.ts` anywhere under src picked up by Vitest. Async route errors automatically propagate under Express 5.

Shared Nunjucks template **layouts/base.njk** (alias `layout.njk`); blocks `pageTitle`, `beforeContent`, `content`, `scripts`. Shared partials **partials/header.njk**, **phase-banner.njk**, **footer.njk**, **error-summary.njk**, **status-tag.njk**, **navigation.njk**. Globals `serviceName`, `demoBanner`; per request `requestId`, `user`, `currentPath`, `csrfToken`. Filters `money`, `date`, `statusLabel`. Error list `errors = [{text, href}]` for summary; GOV.UK individual errors in each macro. Use `{% from "govuk/components/input/macro.njk" import govukInput %}` etc. No crown, coat of arms, Transport fonts or government copyright footer; custom header/footer and Sass `$govuk-font-family: Arial, Helvetica, sans-serif`, `$govuk-include-default-font-face: false`.

Session: `req.session.user`, `req.session.makeClaim` (B4), `req.session.respond` (B5), `req.session.returnTo`. Index signature permits feature-owned fields without editing shared types. Every mutating HTML form includes hidden `_csrf` = `csrfToken`; JSON/upload requests send `X-CSRF-Token`, so multipart can be validated before Multer reads. Token is available in `meta[name="csrf-token"]`. Multer 2, Zod, date-fns, Supertest preinstalled. Server uses bounded in-memory session store for synthetic demo; not production horizontally shared sessions.

UI entry URLs: `/` base landing, `/make-claim/eligibility`, `/make-claim/task-list`, `/respond`, `/dashboard`, `/dashboard/:reference`, `/caseworker`, `/caseworker/:reference`, `/ops`, `/sign-in`, POST `/sign-out`. B4 keeps all routes under `/make-claim`; B5 under `/respond` and `/dashboard`; B6 under its three prefixes. Use native HTML form POST + redirects, retain entered values on errors, and offer “Change” links from check answers. Mock card input must never persist/log PAN/CVV and clearly state no payment is taken.

API client `src/lib/api-client.ts`: `civilApi`, `legacyApi`, `apiForReference(ref)`, `client.get/post/put/delete<T>(path, body?, context?)`, `client.upload<T>(path, file, filename, context?)`, `client.download(path, context?)`; context built with `apiContext(req)` preserves request ID and demo user headers. Paths include `/api`. Non-2xx throws **ApiError** with status/body/requestId. Use `getCombinedClaims(query, context)` to merge modern+legacy pages (request enough rows then sort; UI handles combined pagination). Do not swallow upstream 500s into empty successful screens.

## Seed and reset handoff

B7 generates **160 modern + 40 legacy** synthetic UK claims (£50–£25,000), all states, stable references and IDs, deterministic random seed. At least one expired unanswered modern/legacy claim and one answerable future-deadline claim; relative dates ensure demos remain usable, plus fixed Easter/Christmas/DST historical edges. All claimant dashboards use demo-claimant. Document fixtures beneath `seed/evidence/`; no real personal data. Main smoke claim **000MC001** / code **DEMO2026**, legacy **000LC001**; keep these awaiting response with future deadlines. V300 modern inserts data; `seed/modern/reset.sql` repeats it after clearing business data. Legacy V300 under `seed/legacy/migrations`, matching reset SQL. Bank holiday snapshot `seed/bank-holidays.json` supplied by Stage A. Record actual seed showcase refs in README during Stage C.

## Research notes (read-only; no accounts or submissions)

- GOV.UK https://www.gov.uk/make-court-claim-for-money: “You can apply to a county court to claim money you’re owed by a person or business.” Use “person or business”, “defendant”, “claim amount”, “claim fee”, “What happens next”. Online/paper available; England/Wales only for this demo.
- https://www.moneyclaims.service.gov.uk/eligibility publicly accessible: “You will be asked some questions to check you are eligible to use this service.” Interest must use same rate/start date online. Root `/` returned 404 during research; do not infer logged-in page appearance from it. Public return link is `/return-to-claim`.
- https://design-system.service.gov.uk/patterns/question-pages/: one focused question per page; labels/legends, hints, Back link. `/components/task-list/`: Completed/Incomplete statuses. `/patterns/check-answers/`: summary list with accessible Change links and prefilled answers. `/patterns/confirmation-pages/`: panel reference, what happens next. `/components/file-upload/`: explicit format/size hint and labelled input. Dashboard uses table/cards + tags (not a distinct official dashboard component).
- MIT repositories https://github.com/hmcts/civil-citizen-ui and https://github.com/hmcts/civil-service (Java/Camunda): inspected `src/main/views/features/claim/{task-list,check-answers}.njk`, `src/main/common/form/models/claim/amount/claimAmountRow.ts` (`reason`, `amount`, positive 2dp), `src/main/views/features/claim/yourDetails/timeline.njk`, modern build's Camunda 7.24. Reuse concepts/wording, not a wholesale code fork.
- MIT https://github.com/hmcts/cmc-claim-store: inspected `services/ResponseDeadlineCalculator.java` (original-date offset then roll to next working day), `repositories/ReferenceNumberRepository.java` (DB reference function), `OffersRepository.java` (handwritten SQL), build (JDBI 2.78). Legacy *demo* deliberately uses Date/Calendar as brief requires, even though current upstream calculator now uses LocalDate.
- MIT https://github.com/hmcts/cmc-citizen-frontend: inspected `src/main/features/claim/views/{task-list,check-and-send}.njk`; use grouped task sections and statement of truth/check-and-send pattern. No government logos copied. Upstream versions evolve; this demo intentionally pins requested technology generation.

## Stage A validation versus Stage B work

Skeleton boots/render-checks only: application endpoints, business logic, Camunda BPMN, full forms, seed/reset implementations and Datadog export are Stage B responsibilities. Empty feature folders intentionally register no routes until agents add them. No mock success endpoints hide missing functionality.
