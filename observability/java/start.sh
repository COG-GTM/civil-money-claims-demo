#!/bin/sh
set -eu
# Agent/collector export is configured through DD_* variables by B7.
if [ "${DD_TRACE_ENABLED:-false}" = "true" ]; then
  exec java -javaagent:/app/dd-java-agent.jar -jar /app/app.jar
fi
exec java -jar /app/app.jar
