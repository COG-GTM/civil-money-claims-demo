#!/bin/bash
set -euo pipefail
# The ingress preserves BASE_PATH; each app owns that context rather than relying on stripping.
BASE_PATH="${BASE_PATH%/}"
if [[ ! "$BASE_PATH" =~ ^(/[A-Za-z0-9_-]+)*$ ]]; then
  echo '{"level":"error","service":"demo-supervisor","message":"Invalid BASE_PATH"}' >&2
  exit 1
fi
export BASE_PATH
pids=()
cleanup() {
  trap - EXIT TERM INT
  kill -TERM "${pids[@]}" 2>/dev/null || true
  wait || true
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

/usr/local/bin/docker-entrypoint.sh postgres -c listen_addresses=127.0.0.1 \
  -c max_connections=60 -c shared_buffers=64MB &
pids+=("$!")
for ((i=0; i<120; i++)); do
  if pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null; then break; fi
  if ! kill -0 "${pids[0]}" 2>/dev/null; then exit 1; fi
  sleep 1
done
pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null

export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB"
export DATABASE_USERNAME="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD"
export LOGGING_CONFIG=file:/app/observability/java/logback-spring.xml
export DD_TRACE_HEADER_TAGS=x-request-id:request_id
export SERVER_FORWARD_HEADERS_STRATEGY=NATIVE
agent=()
if [[ "${DD_TRACE_ENABLED:-true}" == true ]]; then agent=(-javaagent:/app/dd-java-agent.jar); fi

gosu app env PORT=8080 SERVER_ADDRESS=127.0.0.1 SERVER_SERVLET_CONTEXT_PATH="$BASE_PATH" \
  DD_SERVICE=civil-case-api DEMO_RESET_SQL=/app/seed/modern/reset.sql \
  java -Xms64m -Xmx384m "${agent[@]}" -jar /app/civil-case-api.jar &
pids+=("$!")
gosu app env PORT=8081 SERVER_ADDRESS=127.0.0.1 SERVER_SERVLET_CONTEXT_PATH="$BASE_PATH/legacy" \
  DD_SERVICE=legacy-claim-store DEMO_RESET_SQL=/app/seed/legacy/reset.sql \
  LEGACY_SEED_LOCATION=filesystem:/app/seed/legacy/migrations \
  java -Xms64m -Xmx256m --add-opens=java.base/java.lang=ALL-UNNAMED "${agent[@]}" \
  -jar /app/legacy-claim-store.jar &
pids+=("$!")

# Do not advertise readiness until both schemas, workflows and backend services are available.
for ((i=0; i<180; i++)); do
  if curl -sf "http://127.0.0.1:8080$BASE_PATH/health" >/dev/null \
    && curl -sf "http://127.0.0.1:8081$BASE_PATH/legacy/health" >/dev/null; then break; fi
  if ! kill -0 "${pids[1]}" 2>/dev/null || ! kill -0 "${pids[2]}" 2>/dev/null; then exit 1; fi
  sleep 1
done
curl -sf "http://127.0.0.1:8080$BASE_PATH/health" >/dev/null
curl -sf "http://127.0.0.1:8081$BASE_PATH/legacy/health" >/dev/null

gosu app env PORT=3001 DD_SERVICE=civil-citizen-ui \
  CIVIL_CASE_API_URL="http://127.0.0.1:8080$BASE_PATH" \
  LEGACY_CLAIM_STORE_URL="http://127.0.0.1:8081$BASE_PATH/legacy" \
  node --max-old-space-size=192 --require ./dist/observability/tracing.js dist/server.js &
pids+=("$!")

# Pass the complete URI to Spring's context path; only one public port is exposed.
cat > /etc/nginx/nginx.conf <<EOF
worker_processes 1;
error_log /dev/stderr warn;
pid /run/nginx.pid;
events { worker_connections 512; }
http {
  include /etc/nginx/mime.types;
  access_log off;
  client_max_body_size 12m;
  map \$http_x_forwarded_proto \$forwarded_proto {
    default \$http_x_forwarded_proto;
    "" \$scheme;
  }
  server {
    listen $PORT;
    proxy_http_version 1.1;
    proxy_set_header Host \$http_host;
    proxy_set_header X-Forwarded-Host \$http_host;
    proxy_set_header X-Forwarded-Proto \$forwarded_proto;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_read_timeout 120s;
    location $BASE_PATH/legacy/ { proxy_pass http://127.0.0.1:8081; }
    location $BASE_PATH/api/ { proxy_pass http://127.0.0.1:8080; }
    location $BASE_PATH/v3/ { proxy_pass http://127.0.0.1:8080; }
    location $BASE_PATH/swagger-ui { proxy_pass http://127.0.0.1:8080; }
    location $BASE_PATH/camunda { proxy_pass http://127.0.0.1:8080; }
    location / { proxy_pass http://127.0.0.1:3001; }
  }
}
EOF
nginx -t
nginx -g 'daemon off;' &
pids+=("$!")
# Any daemon exit stops the whole container, so the platform can restart a coherent stack.
set +e
wait -n "${pids[@]}"
exit $?
