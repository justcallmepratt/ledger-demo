# Business Requirements Document: ledger-core

Project 1 of the Fintech Projects Wiki. Companion documents: [TECH_STACK.md](TECH_STACK.md),
[PROJECT_PLAN.md](PROJECT_PLAN.md) (delivery plan), [ARCHITECTURE.md](ARCHITECTURE.md) and
[API.md](API.md). Design decisions in ARCHITECTURE.md and API.md are binding; this document
states the business need behind them.

## 1. Summary

`ledger-core` is a double-entry ledger and payments API. It records every movement of money
as an immutable transfer between two accounts, rejects bad or duplicate requests, supports
authorise-then-capture payments, publishes every committed change to Kafka and proves the
books balance. It matters because every payments company is built on a ledger, and a ledger
that is wrong, double-counts a retry or cannot be reconciled is the most expensive kind of
bug in fintech.

## 2. Background and problem

Payments systems move money across networks that time out, retry and fail halfway. Three
problems follow.

- **Retries create duplicate payments.** A client that loses a connection cannot tell whether
  the charge happened, so it retries. Without a unique key, the retry is a new charge. Stripe
  describes idempotency keys as the fix: the server stores the key and returns the original
  response instead of creating a second charge
  ([Stripe, "Because nobody likes being charged twice"](https://stripe.dev/blog/because-nobody-likes-being-charged-twice)).
- **Money must never appear or vanish.** Double-entry rules force every transfer to name a
  source and a destination, so funds cannot disappear or be double-spent. Modern Treasury
  argues that every movement must be recorded immutably so that past states can be queried
  for compliance and audits
  ([Modern Treasury, "How to Scale a Ledger, Part I"](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-i)).
- **Mismatched books are costly.** PYMNTS estimates that financial institutions lose about
  98.5 million dollars a year to manual billing and reconciliation inefficiencies
  ([PYMNTS, 2025](https://www.pymnts.com/news/banking/2025/manual-billing-costs-banks-98-5-million-every-year)).
  The figure is an industry estimate, not a measurement of any single firm. The practical
  lesson is that a system should check its own balances continuously instead of waiting for
  a human to find a break.

The most-starred open-source answer to this problem is
[TigerBeetle](https://github.com/tigerbeetle/tigerbeetle) (about 17k GitHub stars), a
purpose-built ledger database. Apache Fineract, Formance and Blnk were also studied.
`ledger-core` takes TigerBeetle's accounting model and implements it on the stack a bank or
payments employer actually runs: Java, Spring, PostgreSQL and Kafka.

## 3. Objectives

| # | Objective | Measure |
|---|---|---|
| O1 | Money is exact and the books always balance | After a load test of at least 100,000 transfers across 1,000 accounts, reconciliation reports `balanced: true` and zero account mismatches |
| O2 | Writes are idempotent | Replaying any transfer id with an identical body returns the stored result and changes no balance in 100% of property-test runs; a different body returns HTTP 409 |
| O3 | Every committed transfer reaches the event feed | Each committed transfer appears on `ledger.transfers.v1` and in the dashboard activity feed within 2 s at p95 on a developer laptop; zero lost events in the outbox test |
| O4 | Correct under concurrency | 32 threads on the same accounts finish with exact expected balances, no deadlocks and no breach of balance limits |
| O5 | Credible as a portfolio piece | Public repo with green CI, coverage gates of 95% (domain) and 90% (service), a README with screenshots and a write-up on the personal site |

## 4. Stakeholders and users

| Role | Need | How they use it |
|---|---|---|
| Hiring manager reviewing the portfolio | Quick evidence of production-shaped fintech engineering | Reads the README, ARCHITECTURE.md and CI results; watches the demo |
| Payments operations analyst | See money movements as they happen and know the books balance | Uses the dashboard: accounts, transfers, pending transfers, live activity, reconciliation status |
| Finance controller / auditor | Immutable history and proof that debits equal credits | Reads transfer history, balance history and the reconciliation report |
| Integrating engineer (client service) | A safe, retry-friendly API with clear error codes | Calls the REST API with client-generated UUIDs; reads OpenAPI |
| Downstream consumer (later projects, for example fraud scoring) | A reliable, ordered event stream | Subscribes to the Kafka topics and dedupes on the CloudEvents id |
| Owner / developer (Prat) | A maintainable codebase with an enforced quality bar | Reviews sprints against the contract; runs CI |

## 5. Scope

**In scope (v1)**

- Accounts with four counters (debits and credits, pending and posted), balance-limit flags,
  optional balance history and a closed flag.
- Single-phase transfers, two-phase transfers (pending, post in full or in part, void,
  expire) and linked batches that succeed or fail together.
- Client-generated UUIDs as idempotency keys; immutable transfers; strictly increasing `seq`.
- Transactional outbox, Kafka publishing (CloudEvents 1.0) and an in-app projector.
- Reconciliation job and endpoint; pending-expiry job.
- REST API with RFC 9457 errors, OpenAPI docs and an SSE activity stream.
- Angular dashboard: accounts, transfers, pending, live activity.
- Local use only: Docker Compose for PostgreSQL and Kafka, GitHub Actions CI.

**Out of scope (v1)**

- Real money, real customer data, card networks, bank rails or KYC.
- Multi-currency conversion and FX (one ledger per currency, no cross-ledger transfers).
- N-leg journal entries and a ledger DSL (linked single-leg transfers cover these cases).
- Authentication, rate limiting and public hosting.
- Multi-node Kafka, multi-region PostgreSQL, sharding.

**Later phases**

- Phase 2: OAuth2 resource server (JWT) with read and write scopes, per-client rate limits,
  audit logging of every write, public demo deployment.
- Change data capture with Debezium instead of the polling outbox.
- Feeding the fraud-scoring project from `ledger.transfers.v1`.

## 6. Business requirements

| ID | Requirement | Rationale | Priority |
|---|---|---|---|
| BR-1 | The system records money movement using double entry: every transfer debits one account and credits another by the same amount | Funds cannot appear or vanish; totals per ledger always match | Must |
| BR-2 | Retrying a request must never move money twice | Networks fail; duplicate charges are a top payments failure | Must |
| BR-3 | Financial history is immutable; mistakes are fixed with new transfers | Audit trail and SOX-style evidence | Must |
| BR-4 | Funds can be reserved first and captured later, in full or in part, or released | Matches card authorisation and marketplace escrow flows | Must |
| BR-5 | Groups of transfers can succeed or fail as one unit | Fee splits and multi-party settlements must not half-apply | Must |
| BR-6 | Every committed change is published to a durable event stream, with no lost and no phantom events | Downstream systems (fraud, notifications, reporting) depend on it | Must |
| BR-7 | The system can prove at any time that stored balances match the transfer history and that debits equal credits | Replaces manual reconciliation with a continuous check | Must |
| BR-8 | Accounts can be protected from overdraft or over-credit by configuration | Wallets and settlement accounts need hard limits | Should |
| BR-9 | Operators can watch activity live and inspect accounts and pending transfers | Operations visibility; also the portfolio demo | Should |
| BR-10 | The API is documented and errors are machine-readable | Integrators need to handle each failure correctly | Should |
| BR-11 | Authentication and rate limiting exist before any public deployment | Security baseline for the internet | Could (phase 2) |

## 7. Functional requirements

| ID | Requirement | Traces to | Acceptance criterion |
|---|---|---|---|
| FR-1 | Create an account with id, ledger, code, name and flags | BR-1, BR-8 | `POST /accounts` returns 201; same id and body returns 200; same id with a different body returns 409 `exists_with_different_fields` |
| FR-2 | Create a single transfer between two different accounts in the same ledger | BR-1 | After a transfer of 1050, debit account `debitsPosted` and credit account `creditsPosted` each rise by exactly 1050 |
| FR-3 | Validate transfers in the documented order and return the first failing result code | BR-1, BR-10 | One test per code in API.md; each returns the specified HTTP status and `code` |
| FR-4 | Reject transfers that breach balance-limit flags | BR-8 | With `debitsMustNotExceedCredits`, a transfer that would make `dp + dP > cP` is rejected with `exceeds_credits`; a concurrency test never sees the bound crossed |
| FR-5 | Treat the client transfer id as the idempotency key | BR-2 | Replaying an identical transfer returns 200 and the stored transfer; balances and event count are unchanged |
| FR-6 | Create pending transfers that reserve funds in the pending counters | BR-4 | Pending adds to `debitsPending` and `creditsPending` only; posted counters unchanged |
| FR-7 | Post a pending transfer in full or in part, or void it | BR-4 | Partial post moves the posted amount and releases the remainder; a pending transfer resolves at most once |
| FR-8 | Expire pending transfers after `timeoutSeconds` | BR-4 | Within 10 s of `expiresAt` the reserve is released and `transfer.expired` is published; post or void after expiry returns `pending_transfer_expired` |
| FR-9 | Accept a batch of up to 100 transfers with linked chains | BR-5 | A failure in a chain rolls back every item in it; the failing item has its own code and the others `linked_event_failed`; separate chains are independent |
| FR-10 | Never update or delete a transfer | BR-3 | A direct `UPDATE` or `DELETE` on `transfers` raises a database error in an integration test |
| FR-11 | Assign each committed transfer a strictly increasing `seq` and a UTC timestamp | BR-3, BR-6 | Concurrent inserts produce unique, increasing `seq` values |
| FR-12 | Write one outbox row per event in the same transaction as the transfer | BR-6 | Force a failure after the insert: no transfer and no outbox row; success: exactly one row |
| FR-13 | Publish outbox rows to Kafka at least once as CloudEvents on `ledger.transfers.v1` and `ledger.accounts.v1` | BR-6 | Embedded-Kafka test consumes the event; killing the publisher mid-batch loses nothing and duplicates carry the same CloudEvents id |
| FR-14 | Reconcile every account and ledger on a schedule and on request | BR-7 | `GET /reconciliation` returns `balanced: true` on a clean run; a deliberately corrupted counter appears in `accountMismatches` |
| FR-15 | Serve account and transfer history with keyset pagination and optional balance history | BR-3, BR-9 | Paging through 10,000 transfers returns each one once in `seq` order; history on a non-history account returns 422 `history_not_enabled` |
| FR-16 | Stream live activity over SSE with replay by `Last-Event-ID` | BR-9 | A client that reconnects with `Last-Event-ID` receives every missed event once |
| FR-17 | Dashboard shows accounts, account detail, transfer form, pending transfers and live feed | BR-9 | Component tests pass; a transfer made in the form appears in the live feed without a page reload |
| FR-18 | Publish OpenAPI and RFC 9457 problem responses | BR-10 | `/v3/api-docs` and Swagger UI load; every error response has `code` |

## 8. Non-functional requirements

| ID | Area | Requirement |
|---|---|---|
| NFR-1 | Correctness and data integrity | Money is a `BIGINT` in minor units end to end, never floating point; overflow is checked with `Math.addExact`; amounts are decimal strings in JSON and `bigint` in the browser. Property tests (jqwik) prove per-ledger `sum dP = sum cP` and `sum dp = sum cp` across random transfer sequences |
| NFR-2 | Concurrency | Accounts are locked with `SELECT ... FOR UPDATE` in ascending id order; no deadlocks and exact balances under 32 concurrent writers |
| NFR-3 | Performance | Single transfer p95 below 50 ms and a sustained 200 transfers per second on a developer laptop with local PostgreSQL; event visible in the feed within 2 s at p95 |
| NFR-4 | Availability | Phase 1 is a single node for local use with no availability target. The design must not lose data on restart: committed transfers and unpublished outbox rows survive and are published after recovery |
| NFR-5 | Security and privacy | No real personal data. Input validation, batch limit 100, request size limits, CORS limited to the web origin, actuator limited to health, info and Prometheus, security headers, secrets only in environment variables. Authentication and rate limiting are phase 2 (see [SECURITY.md](../SECURITY.md)) |
| NFR-6 | Compliance (imitated, not claimed) | This is a portfolio project, not a regulated system, and holds no real money. It imitates: SOX-style audit trails (immutable, timestamped history), PCI DSS scope reduction (the ledger stores no card numbers), Reg E-style error resolution (corrections as new, traceable transfers) and ISO 4217 currency codes. It makes no compliance claim |
| NFR-7 | Time | All timestamps are UTC (`timestamptz`, `Instant`); a `Clock` is injected, never `Instant.now()` in business code |
| NFR-8 | Accessibility | The dashboard meets WCAG 2.1 AA, checked with automated axe tests, keyboard navigation and sufficient colour contrast |
| NFR-9 | Observability | Health and Prometheus metrics via Actuator; gauge `ledger.reconciliation.mismatches`; reconciliation mismatches logged at ERROR |
| NFR-10 | Maintainability | JaCoCo gates in CI: 95% instruction coverage on `ledger-domain`, 90% on `ledger-service`; no skipped tests; CI fails if no integration tests ran |
| NFR-11 | Portability | The full test suite runs without Docker (embedded PostgreSQL and Kafka) |
| NFR-12 | Dependency hygiene | Dependabot weekly; exact versions pinned; new major lines adopted about three months after release |

## 9. Assumptions, constraints and dependencies

- Local only for v1: PostgreSQL 17 and a single-node Kafka 4.1 in Docker Compose. No cloud
  spend.
- GitHub Actions free tier for public repositories; Dependabot is free.
- No external data sources or paid APIs. All data is generated by the demo profile and tests.
- One ledger per currency; ledger 840 (USD, asset scale 2) is seeded.
- Single operator: the owner is product owner. Claude agents act as architect and developers
  (see PROJECT_PLAN.md).
- The Maven Central mirror may be unreachable in the authoring workspace; CI is the
  authoritative build.
- Public repository: no emails, phone numbers or secrets in code, docs or test data.
- The Angular dashboard depends on the frozen API contract in API.md.

## 10. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Balance corruption from a concurrency bug | Medium | High | Ordered locking, concurrency tests, property tests, reconciliation job |
| Backend cannot be compiled in the authoring workspace | High | Medium | CI is the compiler; Sprint 3 item 3.1 reserved for first-run fixes |
| Contract drift between backend and web built in parallel | Medium | Medium | Single API.md; architect reviews both against it |
| Spring Boot 4 / Angular 22 API differences | Medium | Medium | Copy patterns from the owner's working repos; read framework docs |
| Duplicate or lost Kafka events | Low | High | Transactional outbox, idempotent producer with `acks=all`, consumers dedupe on CloudEvents id |
| Scope creep (FX, n-leg entries, auth) | Medium | Medium | Out-of-scope list above; ADR required to change frozen design |
| Public exposure of the unauthenticated API | Low | High | Bind to localhost; documented in SECURITY.md; auth is a gate before any deployment |
| Misread as a compliant product | Low | Medium | Compliance statement in NFR-6 and README |

## 11. Success metrics

- All five objectives O1 to O5 met and demonstrated.
- CI green on `main` with both coverage gates passing and zero skipped tests.
- Reconciliation reports `balanced: true` after the load test.
- v0.1.0 tagged with a README that has screenshots and a short demo GIF.
- A portfolio write-up on the personal site explaining the TigerBeetle-on-PostgreSQL design,
  the outbox and the trade-offs.
- A reviewer can clone, run `docker compose up`, start the API and web app and move money in
  under 10 minutes using only the README.

## 12. Glossary

| Term | Meaning |
|---|---|
| Double-entry | Every transfer debits one account and credits another by the same amount, so totals always match |
| Ledger | A group of accounts that may transact with each other, one currency each (for example 840 = USD) |
| Minor units | The smallest currency unit, for example cents; 1050 means 10.50 USD |
| Idempotency key | A client-chosen id (here the transfer UUID) that makes a retry safe |
| Pending transfer | A reservation of funds that is later posted, voided or expired (authorise, then capture) |
| Linked chain | Transfers in a batch that succeed or fail as one unit |
| Transactional outbox | An event row written in the same database transaction as the change, then published to Kafka |
| Reconciliation | Recomputing balances from the transfer history and comparing them with stored balances |
| CloudEvents | A standard envelope for event data; used for Kafka messages |
| Balance-limit flag | An account setting that stops it going below zero (`debitsMustNotExceedCredits`) or over-credited |
