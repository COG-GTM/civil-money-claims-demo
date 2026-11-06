#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Isolated disposable database: never reset a developer's actual claims database.
db="seed_test_$$"
docker compose exec -T postgres createdb -U claims "$db"
trap 'docker compose exec -T postgres dropdb -U claims "$db"' EXIT HUP INT TERM
sql() { docker compose exec -T postgres psql -U claims -d "$db" -v ON_ERROR_STOP=1 --single-transaction; }
{
  printf 'CREATE SCHEMA civil; CREATE SCHEMA legacy_cmc;\n'
  cat civil-case-api/src/main/resources/db/migration/core/*.sql
  cat legacy-claim-store/src/main/resources/db/migration/legacy/*.sql
  cat civil-case-api/src/main/resources/db/migration/seed/V300__demo_seed.sql
  cat seed/legacy/migrations/V300__demo_seed.sql
} | sql
sql < scripts/seed-assertions.sql
# Both resets are rerunnable in the same transaction; histories must not accumulate.
{
  cat seed/modern/reset.sql seed/legacy/reset.sql seed/modern/reset.sql seed/legacy/reset.sql
  cat scripts/seed-assertions.sql
} | sql
printf 'Seed migration and two consecutive resets passed (160 modern, 40 legacy).\n'
