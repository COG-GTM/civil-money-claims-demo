# Legacy claim store

Synthetic HMCTS demo only. Java 21 / Spring Boot with deliberately old-generation JDBI 2 SQL-object DAOs, handwritten SQL, a large `ClaimService`, and text-column JSON. There are deliberately **no unit tests** in this service; correctness still matters.

Build from the repository root:

```sh
mvn -B -s .mvn/settings.xml -f legacy-claim-store/pom.xml -DskipTests package
```

Run with local PostgreSQL available on port 5433:

```sh
cd legacy-claim-store
java --add-opens=java.base/java.lang=ALL-UNNAMED -jar target/app.jar
```

The `--add-opens` flag is necessary for JDBI 2.78's shaded CGLIB SQL-object implementation on Java 21. The service verifies DAO initialization during startup rather than silently accepting requests with broken proxies. Container startup is owned by `observability/java/start.sh`. `/health` includes database availability.

## Legacy-owned domain logic

| Rule | Implementation |
| --- | --- |
| Claims, validation, fees/interest, responses, default judgment, history and notes | `service/ClaimService.java` |
| Service date + 5 days; response +14; acknowledgment +28 additional days; England/Wales holiday/weekend roll | `service/LegacyResponseDeadlineCalculator.java` |
| Claim JSON blobs, indexed fields, event/note/document SQL | `repository/ClaimRepository.java` |
| Settlement offer SQL | `repository/OffersRepository.java` |
| Concurrent `000LC` reference sequence | `repository/ReferenceNumberRepository.java` |
| REST API, request IDs, safe errors and recent metrics | `web/` |

Deadlines use London calendar dates rather than elapsed 24-hour durations. A scheduled poll moves unanswered overdue claims to `AWAITING_DEFAULT_JUDGMENT`; reads also refresh their state. Responses remain possible until judgment is requested. Acknowledgment is idempotent and only extends an unexpired claim. Admissions and accepted offers are not proof of payment: they do not automatically mark a case settled.

Writes hold the claim row lock so a concurrent response and judgment cannot both succeed. Reset uses an exclusive database advisory lock; ordinary requests share that lock. The configured `DEMO_RESET_SQL` must be B7's plain transactional SQL script. Reset is disabled unless enabled explicitly and a non-empty `X-Demo-Reset-Token` matches configuration. `LEGACY_SEED_LOCATION` adds B7's seed migrations without changing legacy core migration history.

Evidence is stored in PostgreSQL `bytea`. PDF/JPEG/PNG signatures and declared content type must agree, size is limited to 10 MiB, filenames are sanitized, and deletion is limited to drafts. `CLEAN` is a virus-scan **stub**, not a real scanner.

The shared fake-role headers are demo conventions, not production authentication. No card data is accepted by the API; issue only consumes a mock payment reference and statement of truth. Exception logs retain class names and full stack frames but omit exception messages that could expose JDBI bound JSON, security codes or tokens.
