# Tech Stack: ledger-core

Companion to [BRD.md](BRD.md), [ARCHITECTURE.md](ARCHITECTURE.md) and
[PROJECT_PLAN.md](PROJECT_PLAN.md). Version rule: prefer LTS lines; a new major or
non-LTS release waits about three months before adoption. Versions below name the line;
exact versions are pinned in `pom.xml` and `pnpm-lock.yaml`.

## 1. Stack table

| Layer | Choice | Version policy | Why | Alternatives considered |
|---|---|---|---|---|
| Language / runtime | Java 21 LTS, Amazon Corretto | LTS only; move to the next LTS after about 3 months | Owner's core stack; records, pattern matching and virtual threads; `long` with `Math.addExact` for exact money | Kotlin (extra language for no gain here); Java 25 (too new for the rule) |
| Framework | Spring Boot 4.1.x (4.1.2 pinned) | Stay one minor behind newest until it is about 3 months old (4.2.0 is not adopted yet) | Industry standard in banks; Actuator, Security, Kafka and validation built in | Quarkus, Micronaut (smaller hiring signal); Go (not the owner's stack) |
| Data store | PostgreSQL 17 | Major line, current minor via the image tag | ACID, row locks (`FOR UPDATE`, `SKIP LOCKED`), `BIGINT`, triggers for immutability | TigerBeetle itself (purpose-built, but the goal is to show the model on a common stack); MySQL |
| Data access | Spring JDBC (`JdbcClient`) and Flyway migrations | Managed by Spring Boot | Explicit, reviewable SQL on the money path; no hidden dirty-checking writes | JPA/Hibernate (hides locking and writes); jOOQ (extra codegen) |
| Messaging / streaming | Apache Kafka 4.1, KRaft mode, Spring for Apache Kafka | 4.1 line; no ZooKeeper | Durable ordered log; the same topics feed later projects | RabbitMQ (no replayable log); Redpanda (compatible, but Apache Kafka is the reference) |
| Event format | CloudEvents 1.0, structured JSON | Spec 1.0 | Standard envelope with `id` for consumer dedupe | Avro with a schema registry (later upgrade); ad-hoc JSON |
| API style | REST, JSON, RFC 9457 problem details, OpenAPI 3 (springdoc), SSE for the live feed | `/api/v1`; breaking changes need `/v2` | Simple for clients; SSE needs no extra infrastructure | GraphQL (not needed for a transactional API); gRPC; WebSocket |
| Frontend | Angular 22.x, TypeScript, SCSS, pnpm | Latest major that is at least 3 months old | Owner's core frontend stack; strict typing; `bigint` for money | React (not the owner's stack) |
| AI / ML | None in v1 | n/a | The ledger is deterministic by design. The Kafka topic feeds the fraud-scoring project later | n/a |
| Testing | JUnit 5, jqwik (property tests), AssertJ, Spring Boot test slices, embedded PostgreSQL (`io.zonky.test:embedded-postgres`), embedded Kafka (`spring-kafka-test`, KRaft); web: unit and component tests plus axe | Managed by Spring Boot where possible | Whole suite runs without Docker; property tests prove ledger invariants | Testcontainers (needs Docker); H2 (not real PostgreSQL behaviour) |
| Coverage | JaCoCo gates: 95% instructions on `ledger-domain`, 90% on `ledger-service` | Gates fail the build | Keeps the money path tested | Sonar (more setup than needed) |
| Build and dependencies | Maven 3.9+ multi-module (`ledger-domain`, `ledger-service`); pnpm for web; Dependabot weekly | Pinned exact versions; grouped update PRs | Owner's standard; reproducible; Spring Boot updates grouped | Gradle; npm or yarn |
| CI/CD | GitHub Actions: backend job (JDK 21 Corretto, `mvn -B verify`), web job (Node 22, pnpm lint, test, build); read-only token; no deployment | Actions pinned to major tags and updated by Dependabot | Free for public repositories; fails if no integration tests ran or any were skipped | GitLab CI, Jenkins |
| Observability | Spring Boot Actuator, Micrometer, Prometheus endpoint, structured logs | Managed by Spring Boot | Gauge `ledger.reconciliation.mismatches`; health for compose | OpenTelemetry tracing (phase 2) |
| Local dev | Docker Compose: `postgres:17-alpine`, `apache/kafka:4.1.0`, optional Kafka UI (`tools` profile); ports bound to 127.0.0.1; passwords from `.env` | Image tags pinned | One command for infrastructure; compose fails if a password is unset | Running PostgreSQL and Kafka natively |
| Deployment target (phase 2) | A small container host (for example a single VM or a managed container service) behind TLS, with managed PostgreSQL and a managed or single-node Kafka | Decided at phase 2 | Public demo only after OAuth2/JWT and rate limiting exist | Kubernetes (more than a portfolio needs) |

## 2. Architecture sketch

```
   Angular dashboard  ── REST /api/v1 + SSE ──►  ledger-service (Spring Boot)
                                                  │
        api (controllers, problem handler)        │
          └─► TransferService / AccountService    │
                └─► ledger-domain (pure rules)    │
        one DB transaction per linked chain:      │
          lock accounts (ascending id)            │
          → apply rules → insert transfer         │
          → update counters → insert outbox row   │
                                                  ▼
   PostgreSQL 17  ◄── JDBC ──  OutboxPublisher (poll, SKIP LOCKED) ──► Kafka 4.1 (KRaft)
        ▲                                                              ledger.transfers.v1
        │                                                              ledger.accounts.v1
   ActivityProjector (consumer) ◄─────────────────────────────────────────┘
        └─► activity table ─► SSE stream to the dashboard
   PendingExpiryJob (5 s) · ReconciliationJob (5 min) · GET /reconciliation
```

Data flow: a request enters the API, the service locks the touched accounts in id order,
the pure domain rules return new account snapshots or a rejection code, and the service
writes counters, the immutable transfer and an outbox row in one transaction. The
publisher sends outbox rows to Kafka at least once; the projector consumes them to build
the activity table and the SSE feed. Jobs release expired reservations and recompute
balances to prove the books balance.

## 3. Key libraries

- Spring Web MVC: REST controllers.
- Spring JDBC (`JdbcClient`): explicit SQL and `FOR UPDATE` locking.
- Flyway: versioned schema migrations.
- Spring Security: headers, CORS and actuator restrictions now; OAuth2 resource server in
  phase 2.
- Spring Validation (Jakarta Validation): request validation.
- Spring for Apache Kafka: producer (idempotent, `acks=all`) and consumer.
- springdoc-openapi: OpenAPI at `/v3/api-docs` and Swagger UI.
- Micrometer with the Prometheus registry: metrics.
- JUnit 5, AssertJ and jqwik: unit and property tests.
- `io.zonky.test:embedded-postgres` and `spring-kafka-test`: Docker-free integration tests.
- JaCoCo: coverage gates.
- Angular, RxJS and Angular CLI: dashboard; pnpm for packages.
- ESLint (angular-eslint) and axe: lint and accessibility checks for the web app.

## 4. Data sources and external APIs

None. `ledger-core` calls no third-party APIs and uses no paid services.

| Source | Free tier / terms | Limits |
|---|---|---|
| Demo data | Generated by the `dev` Spring profile and tests | Fake accounts only; no real personal data |
| GitHub Actions | Free for public repositories | Job timeouts set to 20 min (backend) and 15 min (web) |
| Docker Hub and GHCR images | Public images (`postgres`, `apache/kafka`, `kafbat/kafka-ui`) | Pulls are rate limited for anonymous users |
| Maven Central and the npm registry | Public | Respect normal rate limits |

## 5. Engineering standards

- Money is integer minor units: `long` in Java, `BIGINT` in SQL, decimal strings in JSON,
  `bigint` in TypeScript. Never `double`, `float`, or `BigDecimal` for stored amounts.
  Overflow-prone arithmetic uses `Math.addExact` and `Math.subtractExact`.
- Time is UTC (`timestamptz`, `Instant`). A `Clock` is injected; business code never calls
  `Instant.now()`.
- Idempotency: client-generated UUIDs for accounts and transfers; same id and same body
  returns the stored result; same id and different body is a 409.
- The ledger is append-only: transfers are never updated or deleted. Corrections are new
  transfers. The database enforces it with a trigger.
- Secrets only in environment variables. `.env.example` and `docker-compose.yml` carry
  names only.
- Tests with every change. Ledger invariants get property tests. Coverage gates: 95%
  (domain) and 90% (service). No skipped tests.
- Conventional commits, one small focused change per branch, one PR per area, never push
  to `main`.
- Public repo hygiene: no email addresses, phone numbers, home addresses or secrets in
  code, docs, test data or commit history.
- Docs and code stay in step: change the contract in the same PR as the code.
- Dependencies pinned; new majors wait about three months.
- Web: follows the owner's design system tokens and WCAG 2.1 AA.

## 6. Local setup

Prerequisites: Java 21 (Amazon Corretto), Maven 3.9+, Node 22, pnpm, Docker.

```bash
cp .env.example .env            # set the two passwords to the same local value
docker compose up -d            # PostgreSQL 17 + Kafka 4.1 (KRaft)

# API on :8080 with demo data
set -a; . ./.env; set +a
cd backend && mvn -pl ledger-service -am spring-boot:run -Dspring-boot.run.profiles=dev

# Dashboard on :4200 (proxies /api to :8080)
cd web && pnpm install && pnpm start

# Tests (no Docker needed: embedded PostgreSQL and Kafka)
cd backend && mvn -B verify
cd web && pnpm lint && pnpm test && pnpm build
```

API docs: <http://localhost:8080/swagger-ui.html>. Kafka topics UI (optional):
`docker compose --profile tools up -d`, then <http://localhost:8081>.
