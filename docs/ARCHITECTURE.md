# Architecture

## Goal

A correct, auditable double-entry ledger that an engineer at a bank or payments company
would recognise as production-shaped: exact integer money, idempotent writes, two-phase
(authorise → capture) transfers, atomic multi-leg movements, an immutable history, a
reliable event feed, and a reconciliation check that proves the books balance.

## Prior art

The data model follows **TigerBeetle** (tigerbeetle/tigerbeetle, Apache-2.0, ~17k
stars, the most-starred open-source ledger), re-implemented on PostgreSQL and Spring:

| TigerBeetle idea | Here |
|---|---|
| Account holds four counters: `debits_pending`, `debits_posted`, `credits_pending`, `credits_posted` | Same columns on `accounts` |
| Account flags `debits_must_not_exceed_credits` / `credits_must_not_exceed_debits`, `history`, `closed` | Same, as booleans |
| `ledger` groups accounts that may transact (one asset/currency); `code` is a user category | Same; `ledger` = ISO 4217 numeric (840 = USD) |
| A transfer moves `amount` from one debit account to one credit account | Same: one debit leg, one credit leg per transfer |
| Client-supplied transfer `id` is the idempotency key | Same (UUID). Retries with an identical body return the original |
| `linked` flag chains transfers so they all succeed or all fail | Same, inside a batch request |
| `pending` / `post_pending_transfer` / `void_pending_transfer` with `timeout` | Same |
| Transfers are immutable; fix mistakes with correcting transfers | Same |

Also studied: Apache Fineract (Java, 2.6k stars: modular accounting, business events to
Kafka, UTC everywhere) and Formance / Blnk (Go, 1.5k / 0.6k stars: inflight balances,
reconciliation). We deliberately do **not** copy Formance's DSL or Blnk's n-leg
transactions; linked single-leg transfers cover the same cases with simpler invariants.

## Components

```
            ┌──────────── web (Angular) ────────────┐
            │ dashboard · accounts · transfers ·    │
            │ pending · live activity (SSE)         │
            └──────────────┬────────────────────────┘
                           │ REST /api/v1 + SSE
┌──────────────────────────▼──────────────────────────────────┐
│ ledger-service (Spring Boot)                                │
│  api ── TransferService ──► ledger-domain (pure rules)      │
│            │  one DB transaction per linked chain:          │
│            │  lock accounts → apply → insert transfer       │
│            │  → insert outbox row                           │
│  OutboxPublisher (scheduled, SKIP LOCKED) ──► Kafka         │
│  ActivityProjector (Kafka consumer) ──► activity table ─► SSE
│  PendingExpiryJob · ReconciliationJob                       │
└──────────────┬─────────────────────────────┬────────────────┘
               │                             │
          PostgreSQL 17                 Kafka (KRaft)
```

## Packages

`dev.justcallmepratt.ledger`

- `ledger-domain`: `domain` (Account, AccountFlags, Transfer, TransferFlags, Ledger,
  AmountMinor, TransferResult codes), `rules` (TransferValidator, BalanceRules,
  PendingRules). Pure functions over immutable records. Applying a transfer to two
  account snapshots returns new snapshots or a `TransferRejected(code)`. No exceptions for
  business outcomes.
- `ledger-service`: `api` (controllers, DTOs, problem handler), `app` (TransferService,
  AccountService, batch orchestration), `persistence` (JDBC repositories, Flyway),
  `events` (outbox, publisher, CloudEvents mapping, projector), `jobs` (expiry,
  reconciliation), `config`.

Use **Spring JDBC (`JdbcClient`)**, not JPA. Ledger writes need explicit `SELECT … FOR
UPDATE`, exact SQL and no hidden dirty-checking writes.

## Domain rules (the contract tests must prove)

Notation: for an account, `dp, dP, cp, cP` = debits pending/posted, credits pending/posted.

1. **Double entry.** Every transfer adds `amount` to exactly one debit-side counter of the
   debit account and the matching credit-side counter of the credit account. Hence, per
   ledger, `Σ dP = Σ cP` and `Σ dp = Σ cp` always. (Property test + reconciliation job.)
2. **Validation order** (first failure wins, result codes in API.md): id valid →
   flags consistent → accounts exist → accounts differ → ledgers match (transfer ledger
   = both account ledgers) → amount > 0 (except post/void) → accounts not closed →
   balance limits → overflow.
3. **Balance limits.** `debits_must_not_exceed_credits`: reject if
   `dp + dP + amount > cP` on the debit account (code `exceeds_credits`).
   `credits_must_not_exceed_debits`: reject if `cp + cP + amount > dP` on the credit
   account (`exceeds_debits`). Pending debits count against the limit.
4. **Two-phase.** `pending` adds to `dp`/`cp`. `post_pending_transfer` with `pending_id`
   moves up to the pending amount from pending to posted (amount omitted or 0 = full
   amount; less = partial post, remainder released). `void_pending_transfer` releases it.
   A pending transfer resolves at most once. Past `expires_at` it cannot be posted or
   voided (`pending_transfer_expired`); the expiry job releases it.
5. **Idempotency.** Transfer and account ids are client-generated UUIDs. Same id + same
   fields → return the stored result (`exists`, HTTP 200). Same id + different fields →
   `exists_with_different_<field>` (HTTP 409). Concurrent duplicates are resolved by the
   primary key: catch the unique violation, re-read, compare.
6. **Linked chains.** In a batch, a run of transfers with `linked` set, ending with the
   first one without it, is atomic. One failure fails the chain: the failing item gets its
   own code, the others `linked_event_failed`. A batch that ends inside an open chain
   fails that chain with `linked_event_chain_open`. Separate chains are independent.
7. **Immutability.** No `UPDATE`/`DELETE` on `transfers` (enforced by a trigger that
   raises). Corrections are new transfers.
8. **Ordering.** Every committed transfer gets a gap-tolerant, strictly increasing
   `seq` (bigserial) and a server `timestamp`. Events carry `seq`.

## Concurrency

Per chain, one `READ COMMITTED` transaction: collect all account ids touched, lock them
with `SELECT … FOR UPDATE` **in ascending id order** (no deadlocks), apply domain rules
in memory, write counters, insert transfers, insert outbox rows, commit. Concurrency
tests: N threads hammering the same accounts must end with exact expected balances and
no deadlocks; a limit-guarded account must never go below its bound.

## Events (transactional outbox)

The outbox row is written in the same transaction as the transfer, so an event is
published if and only if the transfer committed. `OutboxPublisher` polls
(`FOR UPDATE SKIP LOCKED`, batch 100), sends to Kafka with `acks=all` and idempotent
producer, marks `published_at`. Delivery is **at least once**; consumers dedupe on the
CloudEvents `id`. Topics: `ledger.transfers.v1` (key = transfer id),
`ledger.accounts.v1` (key = account id). Payload schema is in API.md.

`ActivityProjector` is an in-app consumer of both topics (its own consumer group) that
upserts a bounded `activity` read table and fans out to SSE clients. It proves the feed
end to end and is the template for later projects (fraud scoring reads the same topic).

## Jobs

- `PendingExpiryJob` (every 5 s): release pending transfers past `expires_at`, emit
  `transfer.expired` events.
- `ReconciliationJob` (every 5 min, and `GET /api/v1/reconciliation`): per account,
  recompute the four counters from `transfers` and compare with stored counters; per
  ledger, check `Σ dP = Σ cP` and `Σ dp = Σ cp`. Mismatches are logged at ERROR and
  exposed as a Micrometer gauge `ledger.reconciliation.mismatches`.

## Persistence

PostgreSQL 17, Flyway migrations in `ledger-service/src/main/resources/db/migration`.
Tables: `ledgers`, `accounts`, `transfers`, `account_balance_history`, `outbox`,
`activity`. Integer money as `BIGINT` with `CHECK (amount >= 0)`. Seed: ledger 840 USD
(asset scale 2) and a demo `dev` profile that creates a few demo accounts.

## Security (phase 1 scope)

Phase 1 runs locally and in CI only, not on the public internet. Spring Security is on:
actuator restricted to `health`/`info`/`prometheus`, CORS limited to the configured web
origin, request size limits, batch size ≤ 100, validated inputs, security headers.
Authentication (OAuth2 resource server / JWT) and rate limiting are phase 2 and must land
before any public deployment.

## Testing strategy

- Domain: JUnit 5 + **jqwik** property tests: random transfer sequences keep invariant 1,
  never violate limits, idempotent replay is a no-op.
- Service: `@WebMvcTest` slices for HTTP/problem mapping; integration tests on **embedded
  PostgreSQL** (`io.zonky.test:embedded-postgres`, real Postgres binaries, no Docker) and
  **embedded Kafka** (`spring-kafka-test`, KRaft): idempotency, linked rollback,
  two-phase, expiry, concurrency, outbox → Kafka → projector, reconciliation.
- Web: unit tests for money parsing/formatting and services, component tests for forms
  and tables, axe accessibility checks.

## Decisions (ADRs)

1. **TigerBeetle model on Postgres** rather than n-leg journal entries. Two legs per
   transfer + linked chains give the same power with simpler invariants and a well-known
   reference to cite.
2. **Pessimistic ordered locking** over optimistic retries: ledgers have hot accounts
   (fee, settlement); retry storms are worse than short waits.
3. **JDBC over JPA** for explicit, reviewable SQL on the money path.
4. **Transactional outbox** over dual writes or CDC: no lost or phantom events, no extra
   infrastructure (Debezium is a later upgrade path).
5. **Amounts as decimal strings in JSON** so JavaScript clients never lose precision.
6. **Embedded Postgres/Kafka in tests** so the full suite runs anywhere, including
   machines without Docker; no "skipped looks like passed" risk.
7. **Spring Boot 4.1.2** (not 4.2.0, released this month) per the three-month rule for new lines.
