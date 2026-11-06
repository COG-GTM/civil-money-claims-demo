#!/bin/sh
set -eu
# Each service owns its transaction; stop rather than concealing a partial reset.
for base in "${CIVIL_CASE_API_URL:-http://localhost:8080}" "${LEGACY_CLAIM_STORE_URL:-http://localhost:8081}"; do
  curl --fail-with-body --silent --show-error -X POST "$base/api/ops/reset" \
    -H "X-Demo-Reset-Token: ${DEMO_RESET_TOKEN:-local-reset-only}" \
    -H 'Content-Type: application/json' -d '{}'
  printf '\n'
done
