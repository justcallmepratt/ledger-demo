# ledger-core

A double-entry ledger and payments API with a Kafka change feed and an Angular
dashboard. Built with Java 21, Spring Boot, PostgreSQL, Kafka and Angular.

The design follows [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle)'s
accounting model (four balance counters per account, client-chosen ids as idempotency
keys, two-phase transfers, linked transfers that succeed or fail together, balance-limit
flags), re-implemented on PostgreSQL with a transactional outbox to Kafka.

## What it shows

- **Exact money.** Integer minor units end to end: `BIGINT` in SQL, `long` with
  overflow checks in Java, decimal strings in JSON, `BigInt` in the browser.
- **Idempotent writes.** Retrying a transfer with the same id returns the original
  result; the same id with different fields is rejected.
- **Authorise → capture.** Pending transfers reserve funds and can be posted (in full or
  in part), voided, or left to expire.
- **Atomic multi-leg movements.** Linked transfers in a batch all succeed or all fail.
- **Immutable history.** Transfers are append-only, enforced by the database.
- **No lost events.** Every committed transfer produces exactly one outbox row, published
  to Kafka at least once, and the dashboard's live feed is built from that stream.
- **Provably balanced books.** A reconciliation job recomputes every balance from the
  transfer history and checks that debits equal credits per ledger.
- **Safe under concurrency.** Accounts are locked in a fixed order, so heavy parallel
  load ends with exact balances and no deadlocks (tested).

## Layout

| Path | What |
|---|---|
| [`backend/`](backend/) | Maven multi-module: `ledger-domain` (pure rules) and `ledger-service` (Spring Boot API) |
| [`web/`](web/) | Angular dashboard |
| [`docs/BRD.md`](docs/BRD.md) | Business requirements: problem, objectives, scope, requirements, risks |
| [`docs/MVP.md`](docs/MVP.md) | MVP definition: press release, FAQ, hypotheses, scope, exit criteria |
| [`docs/BUSINESS_PLAN.md`](docs/BUSINESS_PLAN.md) | Hypothetical business plan: market, competitors, pricing, projections |
| [`docs/TECH_STACK.md`](docs/TECH_STACK.md) | Stack choices, versions, libraries, standards, local setup |
| [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md) | Delivery plan: phases, gates, sprints |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Design, invariants and decision records |
| [`docs/API.md`](docs/API.md) | HTTP, SSE and Kafka event contract |

## Run it locally

Prerequisites: Java 21 (Amazon Corretto), Maven 3.9+, Node 22 with pnpm, Docker.

```bash
cp .env.example .env            # then set the two passwords to the same local value
docker compose up -d            # PostgreSQL 17 + Kafka (KRaft)

# API on :8080 with demo data (the profile seeds a few accounts)
set -a; . ./.env; set +a
cd backend && mvn -pl ledger-service -am spring-boot:run -Dspring-boot.run.profiles=dev

# Dashboard on :4200 (proxies /api to :8080)
cd web && pnpm install && pnpm start
```

- API docs: <http://localhost:8080/swagger-ui.html>
- Kafka topics UI (optional): `docker compose --profile tools up -d`, then <http://localhost:8081>

## Test

```bash
cd backend && mvn -B verify      # unit, property, slice and integration tests + coverage gates
cd web && pnpm lint && pnpm test && pnpm build
```

Integration tests use embedded PostgreSQL and embedded Kafka, so they need no Docker.

## Status

Phase 1 is for local use only. Authentication and rate limiting are planned before any
public deployment (see [SECURITY.md](SECURITY.md)).

## Credits

The dashboard's visual style is inspired by the Cyberpunk 2077 user interface. It is an
unofficial fan design and is not affiliated with CD PROJEKT RED.
