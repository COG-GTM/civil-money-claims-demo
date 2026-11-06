#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${DD_API_KEY:?Set DD_API_KEY in your shell (never commit it)}"
export DD_VERSION="${DD_VERSION:-$(git rev-parse HEAD)}"
# The override keeps unobserved local startup available without any credentials.
exec docker compose -f docker-compose.yml -f observability/docker-compose.datadog.yml up --build -d "$@"
