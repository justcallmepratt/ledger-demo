#!/usr/bin/env bash
# Samples host, JVM and PostgreSQL state every few seconds while the load test runs.
# Usage: sample.sh <output-file> [interval-seconds]    Stop it with kill; it only reads.
set -u
out="${1:?output file}"
interval="${2:-5}"
base="${BASE_URL:-http://localhost:8080}"
pg="${PG_CONTAINER:-ledger-pg}"
db_user="${LEDGER_DB_USER:-ledger}"

: > "$out"
while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  {
    echo "=== $ts"
    echo "loadavg: $(cut -d' ' -f1-3 /proc/loadavg)"
    # us/sy/id/wa over one second
    echo "vmstat(us sy id wa st): $(vmstat 1 2 | tail -1 | awk '{print $13, $14, $15, $16, $17}')"
    curl -fsS --max-time 3 "$base/actuator/prometheus" 2>/dev/null \
      | grep -E '^(hikaricp_connections_(active|idle|pending|max)\{|process_cpu_usage|system_cpu_usage|jvm_threads_live_threads|jvm_gc_pause_seconds_(count|sum)\{|jvm_memory_used_bytes\{area="heap")' \
      | sed 's/^/app: /'
    docker exec "$pg" psql -U "$db_user" -d ledger -At -F ' ' -c \
      "select 'pg_wait: ' || coalesce(wait_event_type,'cpu') || '/' || coalesce(wait_event,'-') || ' x' || count(*) from pg_stat_activity where datname='ledger' and state='active' and pid <> pg_backend_pid() group by 1 order by count(*) desc" 2>/dev/null
    docker exec "$pg" psql -U "$db_user" -d ledger -At -c \
      "select 'pg_idle_in_tx: ' || count(*) from pg_stat_activity where datname='ledger' and state like 'idle in transaction%'" 2>/dev/null
    docker exec "$pg" psql -U "$db_user" -d ledger -At -c \
      "select 'outbox_backlog: ' || count(*) from outbox where published_at is null" 2>/dev/null
  } >> "$out"
  sleep "$interval"
done
