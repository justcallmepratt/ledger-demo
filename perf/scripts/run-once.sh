#!/usr/bin/env bash
# One complete measured run: fresh PostgreSQL, the given jar, k6, database statistics, reconciliation.
# Used by the A/B job, which alternates two jars on the same runner so host noise hits both alike.
# Usage: run-once.sh <jar> <out-dir>      (needs docker, java 21, k6, curl, jq; Kafka on localhost:9092)
set -u
jar="${1:?jar}"
out="${2:?out dir}"
pg="${PG_CONTAINER:-ledger-pg}"
base="${BASE_URL:-http://localhost:8080}"
db="${LEDGER_DB_NAME:-ledger}"
user="${LEDGER_DB_USER:-ledger}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
mkdir -p "$out"

docker rm -f "$pg" > /dev/null 2>&1
docker run -d --name "$pg" -e POSTGRES_DB="$db" -e POSTGRES_USER="$user" -e POSTGRES_PASSWORD="$LEDGER_DB_PASSWORD" \
  -p 127.0.0.1:5432:5432 postgres:17-alpine \
  -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all -c track_io_timing=on \
  -c shared_buffers=1GB -c max_wal_size=2GB -c max_connections=100 -c deadlock_timeout=1s -c log_lock_waits=on > /dev/null
for _ in $(seq 1 60); do docker exec "$pg" pg_isready -h 127.0.0.1 -U "$user" -d "$db" > /dev/null 2>&1 && break; sleep 1; done
docker exec "$pg" psql -U "$user" -d postgres -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" > /dev/null || exit 1

export LEDGER_DB_URL="jdbc:postgresql://localhost:5432/${db}" LEDGER_DB_USER="$user" LEDGER_DB_PASSWORD
export LEDGER_KAFKA_BOOTSTRAP=localhost:9092
java -Xms1g -Xmx2g -XX:StartFlightRecording=filename="$out/ledger.jfr",settings=profile,dumponexit=true,maxsize=300m \
  -jar "$jar" > "$out/app.log" 2>&1 &
pid=$!
up=0
for _ in $(seq 1 120); do
  curl -fsS "$base/actuator/health" 2> /dev/null | grep -q '"status":"UP"' && { up=1; break; }
  kill -0 "$pid" 2> /dev/null || break
  sleep 1
done
if [ "$up" != 1 ]; then echo "::error::application did not start ($jar)"; tail -n 60 "$out/app.log"; exit 1; fi

docker exec "$pg" psql -U "$user" -d postgres -c "select pg_stat_statements_reset()" > /dev/null
"$here/sample.sh" "$out/samples.log" 5 &
sampler=$!
(cd "$root" && OUT_DIR="$out" k6 run --quiet --no-color perf/k6/ledger.js) > "$out/k6.log" 2>&1
k6_status=$?
kill "$sampler" 2> /dev/null

"$here/collect-pg.sh" "$out/pg"
curl -fsS --max-time 120 "$base/api/v1/reconciliation" > "$out/reconciliation.json" || echo '{}' > "$out/reconciliation.json"
jq -e '.balanced == true and (.accountMismatches | length) == 0 and all(.ledgers[]; .balanced)' "$out/reconciliation.json" > /dev/null \
  && echo "reconciliation: PASS" > "$out/reconciliation-verdict.txt" || echo "reconciliation: FAIL" > "$out/reconciliation-verdict.txt"

kill -TERM "$pid" 2> /dev/null
for _ in $(seq 1 60); do kill -0 "$pid" 2> /dev/null || break; sleep 1; done
jfr view hot-methods "$out/ledger.jfr" > "$out/jfr-hot-methods.txt" 2>&1 || true
jfr view gc-pauses "$out/ledger.jfr" > "$out/jfr-gc-pauses.txt" 2>&1 || true
docker rm -f "$pg" > /dev/null 2>&1

status=0
[ "$k6_status" = 0 ] || { echo "::error::k6 exit status $k6_status"; status=1; }
grep -q PASS "$out/reconciliation-verdict.txt" || { echo "::error::reconciliation failed"; status=1; }
python3 -I "$here/report.py" gate "$out/summary.json" || status=1
exit "$status"
