# Demo observability, seed and traffic operations

Synthetic data only. Do not point the traffic driver at a real government service.
No monitors or Slack routing are created by these files.

## Start with Datadog

```sh
# Set DD_API_KEY securely in your environment first. Never save it in git.
export DD_SITE=datadoghq.com DD_ENV=demo
./scripts/up-observability.sh
```

The script sets `DD_VERSION` to `git rev-parse HEAD` unless explicitly overridden,
then merges `docker-compose.yml` with `observability/docker-compose.datadog.yml`.
The overlay enables all three tracers, an internal-only agent on port 8126 and
Docker JSON stdout/stderr log collection. The Docker socket and log directories
are read-only mounts; treat access to this agent container as privileged host
access. No agent ports are published to the internet. Plain `make up` deliberately
works without Datadog credentials (tracing off).

Services: `civil-citizen-ui`, `civil-case-api`, `legacy-claim-store`; environment:
`demo`; version: Git SHA. Application requests carry `X-Request-ID`. JSON logs
include `request_id`, `dd.trace_id`, `dd.span_id`, request timings and exception
stacks. No party bodies, files, tokens or card details are logged. Node starts with
`--require ./dist/observability/tracing.js` before importing Express. Java agent
attachment uses `JAVA_TOOL_OPTIONS` while preserving caller JVM flags. Legacy's
JDBI 2 CGLIB requires `--add-opens=java.base/java.lang=ALL-UNNAMED` on Java 21;
the shared entrypoint adds it only when `DD_SERVICE=legacy-claim-store`.

To run Java on the host, set `LOGGING_CONFIG=file:<absolute-path>/observability/java/logback-spring.xml`;
the Dockerfiles set it already. An API key alone does **not** export APM:
`DD_TRACE_AGENT_URL` must point to a reachable Datadog agent. For Render, Stage C
must configure a private agent/collector and log drain (or an official supported
agent sidecar arrangement), set the unified service tags and secrets, and verify
ingestion. The Compose Docker-socket collector is local-only, not a Render log
drain. Do not expose an unauthenticated APM listener on the public internet.

```sh
docker compose exec datadog-agent agent status
docker compose logs -f citizen-ui civil-case-api legacy-claim-store
```

In Datadog filter logs/APM by `env:demo` and the service names. Request IDs join
the user-visible error reference to the structured error and its trace.

## Seed and reset

`python3 scripts/generate-seed.py` deterministically regenerates both V300
migrations, both reset scripts and PDF/PNG fixtures using only the standard
library. Generated SQL uses the current London date for live claims; references,
UUIDs, parties, amounts, documents and historical edge examples remain stable.
There are 160 modern and 40 legacy claims, all seven states, £50–£25,000 principal,
all owned by `demo-claimant`. All people, companies and transactions are synthetic;
emails use reserved `example.test` and mobile numbers the UK drama range.

| Showcase | Modern | Legacy | Purpose |
|---|---|---|---|
| Live response | `000MC001` | `000LC001` | `DEMO2026`, future deadline after reset |
| Default judgment | `000MC002` | `000LC002` | Expired unanswered claim |
| Easter rollover | `000MC157` | `000LC037` | Good Friday → 7 April 2026 |
| Christmas rollover | `000MC158` | `000LC038` | Christmas → 29 December 2025 |
| Spring clock change | `000MC159` | `000LC039` | Deadline 30 March 2026 |
| Autumn clock change | `000MC160` | `000LC040` | Deadline 26 October 2026 |

Other demo codes are prefix plus six digits, e.g. `MC000002` / `LC000002`.
Fixed historical edge examples are transferred, not active timer jobs.
The checked-in official England/Wales bank-holiday snapshot covers 2019–2028;
refresh it and add a new Flyway migration before relying on dates outside that
range. Never edit a migration already applied to a deployed database.

```sh
make reset                     # both token-protected reset endpoints
./scripts/test-seed.sh          # isolated disposable DB; needs Compose postgres
./scripts/test-java-logging.sh  # exact Boot encoder, correlation and exception stack
node --test scripts/traffic.test.ts
node citizen-ui/node_modules/typescript/bin/tsc -p scripts/tsconfig.json
```

Reset endpoints must check `DEMO_RESET_ENABLED` and `X-Demo-Reset-Token`, then
execute the **entire** corresponding reset SQL in one transaction. Modern reset
first calls `ClaimWorkflow.reset()` and reseeds active workflows after commit.
The SQL does not touch Flyway, Camunda ACT tables or workflow registry tables.
It removes business data, restores seeds and resets sequences to 161/41.
`make reset` stops on the first failed endpoint; it is not an atomic transaction
across services. Stop traffic before reset and rerun both endpoints if interrupted.

## Traffic: real HTML flow 1 + flow 2

```sh
TRAFFIC_BASE_URL=http://localhost:3000 TRAFFIC_ITERATIONS=3 node scripts/traffic.ts
TRAFFIC_BASE_URL=https://your-demo-ui.example TRAFFIC_RATE_PER_MINUTE=60 \
  TRAFFIC_CONCURRENCY=8 TRAFFIC_DURATION_SECONDS=120 node scripts/traffic.ts
```

Each journey signs in as a fake claimant, completes eligibility and all claim
tasks, alternates PDF/PNG upload, confirms mock payment, then responds in a fresh
session (cycling admit / partly admit / reject). It uses only the UI base URL,
native HTTP forms, real session cookies and CSRF tokens, with per-request IDs.
The claimant confirmation's `data-security-code` carries the demo lookup code;
it is never printed. No API shortcut bypasses the UI. The driver checks validation
errors, non-2xx responses and confirmation routes, and exits nonzero on failure.

`TRAFFIC_RATE_PER_MINUTE` is **journeys**, not HTTP requests, per minute (0.1–600;
default 6). Each journey makes many requests. `TRAFFIC_CONCURRENCY` bounds active
journeys (1–50, default 4); rate ticks at capacity are counted as skipped, not
queued without bounds. `TRAFFIC_ITERATIONS` and `TRAFFIC_DURATION_SECONDS`
default to 0 (run until Ctrl-C); use either for bounded runs. Each request times
out after 30 seconds. Logs contain progress, reference and request IDs, not form
bodies. The driver never consumes the reserved seeded smoke claims.

## Parallel-build integration

Root Compose/Makefile, Render, and package files are frozen Stage A files. Stage C
can fold the supplied overlay into the standard observed startup. B1/B3 own the
reset/exception endpoints; B4/B5 own the HTML contracts used by the traffic driver.
Run the driver against the integrated app and confirm deployed APM/logs before
declaring the full on-call demonstration ready.
