-- DBA capture: EXPLAIN (ANALYZE, BUFFERS) for the statements the ledger-service runs most,
-- on the data volume the load test left behind. Run with psql against the `ledger` database.
-- Mutating statements run inside BEGIN ... ROLLBACK, so nothing changes.
\set ON_ERROR_STOP off
\pset pager off
\timing off

select id as tid from transfers order by seq desc offset 500 limit 1 \gset
select transfer_id as ptid from pending_transfers where status = 'PENDING' order by seq desc limit 1 \gset
select debit_account_id as acct from transfers order by seq desc limit 1 \gset
select id as acct2 from accounts where id <> :'acct' order by id limit 1 \gset
select credit_account_id as hot from transfers where code = 3 limit 1 \gset

\echo '=== Q1 transfers findById (replay check, pending lookup): PK lookup + LEFT JOIN pending_transfers'
EXPLAIN (ANALYZE, BUFFERS) SELECT t.seq, t.id, t.debit_account_id, t.credit_account_id, t.amount, t.ledger, t.code,
  t.linked, t.pending, t.post_pending, t.void_pending, t.pending_id, t.timeout_seconds, t.expires_at, t.reference,
  t.created_at, p.status AS pending_status
FROM transfers t LEFT JOIN pending_transfers p ON p.transfer_id = t.id WHERE t.id = :'tid';

\echo '=== Q2 lock the two accounts of a transfer (SELECT ... ORDER BY id FOR UPDATE)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT id, ledger, code, name, debits_must_not_exceed_credits, credits_must_not_exceed_debits,
  history, closed, debits_pending, debits_posted, credits_pending, credits_posted, created_at
FROM accounts WHERE id IN (:'acct', :'acct2') ORDER BY id FOR UPDATE;
ROLLBACK;

\echo '=== Q3 counters update (account row, the hot-row write)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) UPDATE accounts SET debits_pending = debits_pending, debits_posted = debits_posted + 1,
  credits_pending = credits_pending, credits_posted = credits_posted WHERE id = :'hot';
ROLLBACK;

\echo '=== Q4 transfer insert + outbox insert shape (INSERT ... ON CONFLICT DO NOTHING RETURNING seq)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) INSERT INTO transfers (id, debit_account_id, credit_account_id, amount, ledger, code, linked,
  pending, post_pending, void_pending, pending_id, timeout_seconds, expires_at, reference, created_at)
VALUES (gen_random_uuid(), :'acct', :'acct2', 1, 840, 1, false, false, false, false, null, 0, null, 'explain', now())
ON CONFLICT (id) DO NOTHING RETURNING seq;
ROLLBACK;

\echo '=== Q5 account transfers page (two index scans merged), newest first'
EXPLAIN (ANALYZE, BUFFERS) SELECT t.seq, t.id, t.debit_account_id, t.credit_account_id, t.amount, t.ledger, t.code,
  t.linked, t.pending, t.post_pending, t.void_pending, t.pending_id, t.timeout_seconds, t.expires_at, t.reference,
  t.created_at, p.status AS pending_status
FROM ((SELECT * FROM transfers WHERE debit_account_id = :'hot' AND seq < 9223372036854775807 ORDER BY seq DESC LIMIT 21)
      UNION ALL
      (SELECT * FROM transfers WHERE credit_account_id = :'hot' AND seq < 9223372036854775807 ORDER BY seq DESC LIMIT 21)
     ) t LEFT JOIN pending_transfers p ON p.transfer_id = t.id ORDER BY t.seq DESC LIMIT 21;

\echo '=== Q6 pending transfers list (status filter, keyset by seq)'
EXPLAIN (ANALYZE, BUFFERS) SELECT t.seq, t.id, t.debit_account_id, t.credit_account_id, t.amount, t.ledger, t.code,
  t.linked, t.pending, t.post_pending, t.void_pending, t.pending_id, t.timeout_seconds, t.expires_at, t.reference,
  t.created_at, p.status AS pending_status
FROM pending_transfers p JOIN transfers t ON t.id = p.transfer_id
WHERE p.status = 'PENDING' AND p.seq < 9223372036854775807 ORDER BY p.seq DESC LIMIT 51;

\echo '=== Q7 all transfers page (keyset by seq)'
EXPLAIN (ANALYZE, BUFFERS) SELECT t.seq, t.id, p.status AS pending_status
FROM transfers t LEFT JOIN pending_transfers p ON p.transfer_id = t.id
WHERE t.seq < 9223372036854775807 ORDER BY t.seq DESC LIMIT 51;

\echo '=== Q8 outbox poll (FOR UPDATE SKIP LOCKED, batch 100)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT id, event_id, topic, message_key, event_type, CAST(payload AS text) AS payload_text,
  created_at FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo '=== Q9 outbox mark published (UPDATE by primary key)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) UPDATE outbox SET published_at = now(), attempts = attempts + 1
WHERE id = (SELECT min(id) FROM outbox WHERE published_at IS NULL);
ROLLBACK;

\echo '=== Q10 activity prune (DELETE below the newest 1000, run once per consumed event)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) DELETE FROM activity WHERE id <= (SELECT id FROM activity ORDER BY id DESC OFFSET 1000 LIMIT 1);
ROLLBACK;

\echo '=== Q11 balance history insert'
\echo '(covered by pg_stat_statements; plain INSERT, one index maintained)'
