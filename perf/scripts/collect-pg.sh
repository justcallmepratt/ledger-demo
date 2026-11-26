#!/usr/bin/env bash
# Dumps PostgreSQL statistics after a run: top statements by total time (pg_stat_statements),
# database, WAL and table counters. Usage: collect-pg.sh <output-dir>
set -u
dir="${1:?output dir}"
pg="${PG_CONTAINER:-ledger-pg}"
db_user="${LEDGER_DB_USER:-ledger}"
mkdir -p "$dir"

psqlq() { docker exec "$pg" psql -U "$db_user" -d "$1" "${@:2}"; }

# pg_stat_statements lives in the `postgres` database (so Flyway sees an empty `ledger` schema);
# its view covers every database, filtered here to `ledger`.
psqlq postgres --csv -c "
  select s.queryid, s.calls, round(s.total_exec_time::numeric, 1) as total_ms,
         round(s.mean_exec_time::numeric, 3) as mean_ms, round(s.max_exec_time::numeric, 1) as max_ms,
         s.rows, s.shared_blks_hit, s.shared_blks_read, s.wal_bytes,
         left(regexp_replace(s.query, '\s+', ' ', 'g'), 240) as query
  from pg_stat_statements s join pg_database d on d.oid = s.dbid
  where d.datname = 'ledger' order by s.total_exec_time desc limit 30" > "$dir/pg_stat_statements_top.csv" 2>"$dir/pg_stat_statements_top.err"

psqlq postgres -x -c "
  select calls, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms,
         round(max_exec_time::numeric, 1) as max_ms, rows, query
  from pg_stat_statements s join pg_database d on d.oid = s.dbid
  where d.datname = 'ledger' order by total_exec_time desc limit 15" > "$dir/pg_stat_statements_top.txt" 2>&1

psqlq ledger -x -c "select xact_commit, xact_rollback, blks_read, blks_hit, tup_inserted, tup_updated, tup_deleted, deadlocks, temp_files, blk_read_time, blk_write_time from pg_stat_database where datname = 'ledger'" > "$dir/pg_stat_database.txt" 2>&1
psqlq ledger -x -c "select * from pg_stat_wal" > "$dir/pg_stat_wal.txt" 2>&1
psqlq ledger -c "select relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read, idx_scan, n_tup_upd, n_tup_hot_upd from pg_stat_user_tables order by n_tup_upd + n_live_tup desc" > "$dir/pg_stat_user_tables.txt" 2>&1
psqlq ledger -c "select relname, indexrelname, idx_scan, idx_tup_read from pg_stat_user_indexes order by idx_scan desc" > "$dir/pg_stat_user_indexes.txt" 2>&1
psqlq ledger -At -c "select 'transfers=' || (select count(*) from transfers) || ' accounts=' || (select count(*) from accounts) || ' outbox_unpublished=' || (select count(*) from outbox where published_at is null) || ' outbox_total=' || (select count(*) from outbox) || ' history=' || (select count(*) from account_balance_history)" > "$dir/row_counts.txt" 2>&1
psqlq postgres -At -c "show shared_buffers; show synchronous_commit; show fsync; show max_connections" > "$dir/pg_settings.txt" 2>&1

# --- DBA review inputs -------------------------------------------------------------------------
docker cp "$(dirname "$0")/explain.sql" "$pg:/tmp/explain.sql" >/dev/null 2>&1
psqlq ledger -f /tmp/explain.sql > "$dir/explain_analyze.txt" 2>&1

psqlq postgres --csv -c "
  select s.queryid, s.calls, round(s.mean_exec_time::numeric, 3) as mean_ms, round(s.max_exec_time::numeric, 1) as max_ms,
         round(s.stddev_exec_time::numeric, 3) as stddev_ms, s.wal_bytes,
         left(regexp_replace(s.query, '\s+', ' ', 'g'), 160) as query
  from pg_stat_statements s join pg_database d on d.oid = s.dbid
  where d.datname = 'ledger' and s.calls > 100 order by s.mean_exec_time desc limit 15" > "$dir/pg_stat_statements_by_mean.csv" 2>&1

psqlq ledger -c "
  select c.relname, c.reloptions, pg_size_pretty(pg_relation_size(c.oid)) as heap,
         pg_size_pretty(pg_indexes_size(c.oid)) as indexes, t.n_live_tup, t.n_dead_tup, t.n_tup_upd, t.n_tup_hot_upd,
         t.autovacuum_count, t.autoanalyze_count, t.last_autovacuum, t.last_autoanalyze
  from pg_class c join pg_stat_user_tables t on t.relid = c.oid order by pg_relation_size(c.oid) desc" > "$dir/table_health.txt" 2>&1
psqlq ledger -c "
  select s.relname, s.indexrelname, s.idx_scan, pg_size_pretty(pg_relation_size(s.indexrelid)) as size,
         pg_get_indexdef(s.indexrelid) as definition
  from pg_stat_user_indexes s order by s.idx_scan" > "$dir/index_usage.txt" 2>&1
psqlq ledger -c "
  select a.indrelid::regclass as tbl, a.indexrelid::regclass as idx, b.indexrelid::regclass as redundant_with
  from pg_index a join pg_index b on a.indrelid = b.indrelid and a.indexrelid <> b.indexrelid
   and a.indkey::text = b.indkey::text and a.indisunique >= b.indisunique" > "$dir/duplicate_indexes.txt" 2>&1
psqlq ledger -c "select * from pg_stat_checkpointer" > "$dir/pg_stat_checkpointer.txt" 2>&1
psqlq ledger -c "select * from pg_stat_bgwriter" >> "$dir/pg_stat_checkpointer.txt" 2>&1
psqlq postgres -At -c "select name || ' = ' || setting || coalesce(unit, '') from pg_settings where name in ('autovacuum','autovacuum_naptime','autovacuum_vacuum_scale_factor','autovacuum_vacuum_insert_scale_factor','autovacuum_analyze_scale_factor','autovacuum_vacuum_cost_limit','autovacuum_max_workers','max_connections','shared_buffers','wal_level','synchronous_commit','fsync','full_page_writes','wal_compression','checkpoint_timeout','max_wal_size','random_page_cost','effective_io_concurrency') order by 1" > "$dir/pg_settings_dba.txt" 2>&1
exit 0
