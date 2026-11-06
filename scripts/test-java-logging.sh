#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mvn -q -s .mvn/settings.xml -f civil-case-api/pom.xml dependency:build-classpath -Dmdep.outputFile="$tmp/classpath"
DD_SERVICE=civil-case-api DD_ENV=demo DD_VERSION=logging-test \
  java --class-path "$(cat "$tmp/classpath")" observability/java/LoggingSmoke.java \
  "file:$(pwd)/observability/java/logback-spring.xml" > "$tmp/output"
python3 - "$tmp/output" <<'PY'
import json, sys
records = [json.loads(line) for line in open(sys.argv[1]) if line.startswith('{')]
record, = [r for r in records if r.get('request_id') == 'logging-smoke']
for name, expected in {'service':'civil-case-api','env':'demo','version':'logging-test',
                       'dd.trace_id':'123','dd.span_id':'456','method':'POST',
                       'path':'/synthetic','status':500,'duration_ms':12,
                       'error.kind':'IllegalStateException'}.items():
    assert record[name] == expected, (name, record.get(name))
assert 'Synthetic logging smoke failure' in record['error.message']
assert 'LoggingSmoke.main' in record['error.stack']
assert record['timestamp']
print('Java JSON logging passed: HTTP fields, trace/span correlation and full exception stack.')
PY
