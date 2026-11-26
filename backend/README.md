# ledger-core backend

A double-entry ledger with a TigerBeetle-style model (accounts with four counters,
two-leg transfers, linked chains, two-phase pending/post/void) on PostgreSQL and Spring Boot
4.1.2, Java 21. Design: [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md). HTTP and event
contract: [../docs/API.md](../docs/API.md).

## Modules

| Module | What |
|---|---|
| `ledger-domain` | Pure Java 21, no Spring. Records (`Account`, `Transfer`, flags, `PendingStatus`, `Ledger`), result codes (`TransferResult`) and `TransferEngine`, the pure rules. JUnit 5 + jqwik. Coverage gate 95% |
| `ledger-service` | Spring Boot app. `api` (controllers, DTOs, problem mapping), `app` (services, cursors), `persistence` (`JdbcClient` repositories), `events` (outbox, publisher, projector, SSE), `jobs` (expiry, reconciliation), `config`. Flyway migrations in `src/main/resources/db/migration`. Coverage gate 90% |

## Build and test

```bash
mvn -B verify          # unit, property, WebMvc slice and integration tests + coverage gates
```

Integration tests (`*IntegrationTest`) run on a real PostgreSQL started in-process by
`io.zonky.test:embedded-postgres` and a KRaft Kafka from `@EmbeddedKafka`. No Docker needed.
(The embedded PostgreSQL refuses to run as root.)

## Run locally

You need PostgreSQL 17 and, for the event feed, Kafka (KRaft). Settings come from
environment variables; nothing secret is in the repo.

```bash
export LEDGER_DB_URL=jdbc:postgresql://localhost:5432/ledger
export LEDGER_DB_USER=...          # your local database user
export LEDGER_DB_PASSWORD=...      # and password
export LEDGER_KAFKA_BOOTSTRAP=localhost:9092   # default
export LEDGER_WEB_ORIGIN=http://localhost:4200 # default, CORS origin of the dashboard
mvn -pl ledger-service -am spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile seeds five demo accounts and a handful of transfers (idempotently, so
restarts are safe). Then: Swagger UI at <http://localhost:8080/swagger-ui.html>, OpenAPI at
`/v3/api-docs`, health at `/actuator/health`, metrics at `/actuator/prometheus`.
To run without Kafka set `ledger.kafka.enabled=false` (`LEDGER_KAFKA_ENABLED` style
overrides work through Spring's relaxed binding): the API works, events accumulate in the
outbox until a publisher runs.

| Variable | Default | Meaning |
|---|---|---|
| `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD` | required | datasource |
| `LEDGER_DB_POOL_SIZE` | 20 | Hikari pool size |
| `LEDGER_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `LEDGER_WEB_ORIGIN` | `http://localhost:4200` | allowed CORS origin(s), comma separated |
| `PORT` | 8080 | HTTP port |

## Design highlights (for a reviewer)

- **Exact money.** `long` minor units, `BIGINT CHECK (>= 0)`, decimal strings in JSON,
  `Math.addExact` everywhere; overflow is a result code (`overflows_balance`), not an exception.
- **Business outcomes are values.** `TransferEngine.apply` returns `Applied` or
  `Rejected(code)`; validation order is fixed and tested code by code.
- **Properties, not just examples.** jqwik runs random transfer sequences (single, pending,
  post, partial post, void, expiry, replays) and checks that per ledger
  `Σ debitsPosted = Σ creditsPosted` and `Σ debitsPending = Σ creditsPending`, that limit
  flags are never violated, and that replaying an accepted transfer is a no-op.
- **Append-only ledger.** `transfers` is insert-only, enforced by a trigger that also blocks
  `TRUNCATE`; pending state lives in `pending_transfers`; corrections are new transfers.
- **No deadlocks by construction.** One transaction per linked chain; every account the chain
  touches is locked `SELECT ... FOR UPDATE ORDER BY id`, always in the same single method.
  A concurrency test runs 8 threads x 200 transfers over 4 accounts and opposing chains.
- **Idempotency.** Client UUIDs are the key. Same body replays, different body conflicts
  (`exists_with_different_fields` naming the fields). Races are settled by the primary key
  using `INSERT ... ON CONFLICT DO NOTHING`, then re-read and compare. A committed chain
  replayed in full is a no-op.
- **Transactional outbox.** The event row is written in the transfer's transaction; a
  scheduled publisher uses `FOR UPDATE SKIP LOCKED`, an idempotent `acks=all` producer and
  CloudEvents JSON. Delivery is at least once; the in-app projector dedupes on the event id.
- **Live feed.** `ActivityProjector` (own consumer group) fills a bounded `activity` table
  and pushes to SSE clients, with heartbeats and `Last-Event-ID` replay from the table.
- **Reconciliation.** Recomputes every counter from the immutable transfers in a read-only
  `REPEATABLE READ` snapshot and checks each ledger balances; exposed at
  `GET /api/v1/reconciliation` and as the gauge `ledger.reconciliation.mismatches`. A test
  corrupts a counter by hand and watches it get caught.
- **Time is injected.** A `Clock` bean everywhere (timestamps truncated to microseconds),
  so expiry tests move time instead of sleeping.
- **Security (phase 1).** Stateless, API open, actuator limited to health/info/prometheus,
  CORS from `LEDGER_WEB_ORIGIN`, security headers, 256 KiB body limit, validated inputs,
  RFC 9457 problems with stable `code`s. Authentication and rate limiting are phase 2.
- **Known trade-off.** `seq` is assigned at insert, so under concurrency it can commit out
  of order; newest-first paging by `seq` is exact for committed rows at read time but a
  late-committing lower `seq` can appear after a client has paged past it.
