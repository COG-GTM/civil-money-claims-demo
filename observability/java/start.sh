#!/bin/sh
set -eu
# Preserve caller JVM flags and avoid attaching the same agent twice.
export DD_ENV="${DD_ENV:-demo}" DD_LOGS_INJECTION="${DD_LOGS_INJECTION:-true}"
export DD_TRACE_HEADER_TAGS="${DD_TRACE_HEADER_TAGS:-x-request-id:request_id}"
# JDBI 2's CGLIB SQL-object proxies require this Java 21 compatibility flag.
if [ "${DD_SERVICE:-}" = "legacy-claim-store" ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} --add-opens=java.base/java.lang=ALL-UNNAMED"
  export JAVA_TOOL_OPTIONS
fi
if [ "${DD_TRACE_ENABLED:-false}" = "true" ]; then
  case "${JAVA_TOOL_OPTIONS:-}" in
    *-javaagent:*dd-java-agent*) ;;
    *) JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -javaagent:/app/dd-java-agent.jar" ;;
  esac
  export JAVA_TOOL_OPTIONS
fi
exec java -jar /app/app.jar
