# Performance

Load test, p95 baseline, tuning log and DBA review for the `ledger-core` API (phase 1). Everything
runs in GitHub Actions: [`.github/workflows/perf.yml`](../.github/workflows/perf.yml), scenarios in
[`perf/k6/`](../perf/k6/).

## Environment

GitHub-hosted `ubuntu-latest`, 4 vCPU / 16 GB, everything on one host: k6, the jar (Corretto 21, `-Xms1g -Xmx2g`,
JFR on, virtual threads, Hikari 20), PostgreSQL 17 (`shared_buffers=1GB`, `fsync` and `synchronous_commit` on,
`pg_stat_statements`), Kafka 4.1 (KRaft, the outbox publisher and the activity projector run). The CPU model
differs between runs (EPYC 9V45, EPYC 9V74, Xeon 8573C). That is the biggest source of noise, see Method.

## Workload

All scenarios run at once for 30 s warm-up + 30 s ramp (excluded) + 200 s steady state. Targets per second:
`single_transfers` 150 random pairs from 200 accounts (40 are `debitsMustNotExceedCredits`, funded by a settlement
account); `hot_account` 60 transfers all crediting one fee account; `linked_batches` 15 batches of 5-10 linked
legs (about 113 transfers/s); `two_phase` 40 pending then post / partial post / void (10 % left to expire);
`reads` 120 (account, account transfers page, pending list); `idempotent_retries` 40 (90 % replays, 10 %
conflicts). Total about 460 requests/s, 0 % errors, 0 dropped iterations. Amounts are integer strings. The run
fails unless `GET /reconciliation` is balanced with no account mismatch (checked by k6 and again by the workflow).

## Method (ECC `benchmark-optimization-loop`)

Frozen workload and metric (steady-state p95 per scenario, k6 `http_req_duration`). Hard constraints: `mvn -B verify`
with the coverage gates (run by the `verify` job on every push), reconciliation balanced, errors under 1 %. One hypothesis
per commit, kept only if p95 improves beyond noise.

Noise turned out to be large: **the same commit measured 401, 51 and 20 ms p95 (`single_transfers`) on three
shared runners** ([run 1](https://github.com/justcallmepratt/ledger-demo/actions/runs/45785514493),
[2](https://github.com/justcallmepratt/ledger-demo/actions/runs/45786511462),
[3](https://github.com/justcallmepratt/ledger-demo/actions/runs/45787443856)). So candidates are judged by an
interleaved A/B on one runner (`workflow_dispatch` with `base_ref`: base, cand, cand, base, fresh database each phase).
Within a good runner, repeat rounds of the same jar differ by 3-10 % on writes and up to 40 % on tiny reads
numbers. Two A/Bs were distorted by the host and are reported as such below.

## Results: original code vs final (A/B on one Xeon 8573C runner, [run](https://github.com/justcallmepratt/ledger-demo/actions/runs/45802623349))

p95 / p99 in ms, per round (base, base | final, final); p50 is one round. Throughput and errors are identical
(0 % errors, target rates met).

| Scenario | req/s | p50 base -> final | p95 base | p95 final | p99 base | p99 final |
|---|---:|---|---|---|---|---|
| single_transfers | 150 | 4.3 -> 4.2 | 16.0, 21.5 | 17.6, 18.2 | 52, 69 | 66, 60 |
| hot_account | 60 | 4.9 -> 4.7 | 17.9, 31.1 | 21.2, 20.8 | 75, 133 | 90, 88 |
| linked_batches | 15 (113 transfers/s) | 18.0 -> 15.6 | 49.5, 62.5 | 46.5, 50.4 | 85, 121 | 96, 97 |
| two_phase | 76 | 5.5 -> 5.1 | 17.0, 21.3 | 17.5, 18.9 | 44, 63 | 54, 47 |
| reads | 120 | 1.45 -> 1.44 | 4.4, 5.4 | 4.5, 5.1 | 11, 25 | 14, 13 |
| idempotent_retries | 40 | 2.8 -> 1.1 | 9.6, 14.1 | 3.7, 4.7 | 48, 59 | 11, 13 |

## Hypotheses

| # | Change | Result | Decision |
|---|---|---|---|
| 1 | Answer a single-transfer replay from a plain read before any transaction or lock; drop the duplicate `findById` for single-item chains | `idempotent_retries` p95 -65 to -68 % in both A/Bs (9.2/5.6 -> 2.4/2.3 ms, [run](https://github.com/justcallmepratt/ledger-demo/actions/runs/45788451955)); writes -4 to -12 %, inside noise | kept |
| 2 | One `UPDATE ... FROM (VALUES ...)` for all touched accounts instead of one per account | `linked_batches` p95 32.5/33.5 -> 28.3/30.5 ms (-11 %, rounds do not overlap, [run](https://github.com/justcallmepratt/ledger-demo/actions/runs/45796286973)); other scenarios neutral | kept |

Caveat: one cumulative A/B on an EPYC 9V74 runner showed the final jar slower (p95 `single_transfers` 17 -> 41 ms,
[run](https://github.com/justcallmepratt/ledger-demo/actions/runs/45799422728)) while the base rounds were steady.
The order-reversed rerun on another host did not reproduce it, and a second 9V74 A/B was degraded for both jars
([run](https://github.com/justcallmepratt/ledger-demo/actions/runs/45792319633)), so it is treated as a host effect, but it is not
proven. Re-run `Perf` with `base_ref` before relying on these numbers.

Considered, not done (small or off the critical path, scope kept proportionate): Hikari pool size (pending
requests were rare, average 2.5 connections busy of 20), `fillfactor` on `accounts` (see below), batching the
`activity` prune, JDBC batch inserts for outbox/history. Nothing that weakens durability was tried
(`synchronous_commit`, `fsync` stay on).

## Current bottleneck

Row-lock queueing on account rows, then commit fsync. The statement that locks the accounts
(`SELECT ... ORDER BY id FOR UPDATE`) is the largest DB time (about 40 % of statement time, mean 0.3-1.5 ms,
outliers 0.4-0.9 s on slow hosts); `hot_account` and `linked_batches` (up to 11 accounts per lock) pay most.
CPU is about 45 % idle, JFR is flat (no hot method above 6 %), GC pauses total under 1 s in a run. Per-run
`hikaricp_connections_pending` showed short bursts (max 4 to 152) during host stalls, not a steady queue.

## DBA review (EXPLAIN, indexes, pool, growth)

- **Plans** (`EXPLAIN (ANALYZE, BUFFERS)` in the job log, `perf/scripts/explain.sql`): every hot statement is an
  index scan on the primary key, `(account, seq DESC)` or `(status, seq DESC)`, 0.15-0.7 ms, all buffers hit.
  Largest background cost: the activity prune `DELETE` runs once per consumed event (93k times, 15-20 % of DB time).
- **Indexes**: all used by the load except `balance_history_account_idx`, `accounts_created_idx` (endpoints not
  in the workload) and `transfers_pending_id_idx` (no lookup by `pending_id` yet). No duplicate (the report flags
  `outbox_pkey` vs the partial `outbox_unpublished_idx`, a false positive). Covering indexes for keyset paging are
  not needed: heap fetches are cached and `INCLUDE` would only add write cost.
- **Hot rows**: `accounts` updates are 99.7 % HOT (162,615 of 163,174) at the default fillfactor, because no counter
  column is indexed and the table is tiny; lowering `fillfactor` would gain nothing, so it was not changed.
- **Pool**: Hikari 20 against `max_connections` 100 leaves room for 4 instances; the pool is not saturated.

If this were production (not implemented): partition `transfers` and `account_balance_history` by `seq` range
(for example 50 M rows) and make reconciliation incremental per partition; delete published `outbox` rows in
batches or partition it by day and drop old partitions (it is 150 MB for 93k rows, and its updates are not HOT
because `published_at` is in the partial-index predicate); keep `activity` bounded but prune every N events;
set per-table autovacuum thresholds for `accounts`, `pending_transfers` and `outbox` (scale factor about 0.02).

## Phase 2 SLO targets (4 vCPU runner, this mix, about 460 req/s)

Derived from the worst steady rounds above with about 1.5x headroom: `POST /transfers` p95 < 25 ms at 150/s;
hot-account transfer p95 < 30 ms at 60/s; linked batch (up to 10 legs) p95 < 70 ms at 15/s; two-phase step p95 < 25 ms;
reads p95 < 8 ms at 120/s; idempotent replay p95 < 8 ms at 40/s; p99 < 150 ms everywhere; errors < 0.1 %;
reconciliation balanced after every run. Authentication and rate limiting (phase 2) will add cost: re-measure.

## Next steps

1. Re-run the cumulative A/B on two more runners to close the caveat above. 2. Shorten lock hold time further: fewer
round trips per leg (batch outbox and history inserts), then measure the 4-vCPU ceiling by raising `RATE_SCALE`.
3. Batch the activity prune and outbox marking. 4. Pin the k6 version (the workflow uses the latest, 2.4.0 in these runs).

## Run it

Actions -> Perf -> Run workflow (`rate_scale`, `hold_s`; `base_ref` for an A/B). Pushes to `perf/**` run it
automatically. Artifact: `summary.json`, k6 log, JFR, `pg_stat_statements` top queries, EXPLAIN output, samples.
