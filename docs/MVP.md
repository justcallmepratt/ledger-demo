# MVP Definition: ledger-core

Working-backwards document in the style of an Amazon PR/FAQ. Everything about customers,
launch and quotes is hypothetical; this is a portfolio project. Companion documents:
[BRD.md](BRD.md), [TECH_STACK.md](TECH_STACK.md), [PROJECT_PLAN.md](PROJECT_PLAN.md),
[BUSINESS_PLAN.md](BUSINESS_PLAN.md).

## 1. Press release

**ledger-core launches an open, retry-safe ledger API for teams that move money**

*Double-entry accounting, authorise-then-capture payments and a live Kafka event feed, with
a built-in proof that the books balance.*

**Launch day (hypothetical)** — Today ledger-core, a double-entry ledger and payments API,
reached its first public release. It gives seed-stage fintechs and marketplaces the part of
the stack that is easiest to get wrong and most expensive to fix: recording who owes whom.

Teams building a wallet, a marketplace payout flow or a lending product usually start with a
balance column and an update statement. Then a client retries a timed-out request and a
customer is charged twice, two requests race and a balance goes negative, or finance asks
why the totals do not match the bank. ledger-core replaces that column with a ledger that is
correct by construction. Every transfer debits one account and credits another by the same
exact amount. Every request carries an id, so a retry returns the original result instead of
moving money again. Funds can be reserved and captured later, in full or in part. Groups of
transfers succeed or fail together. History cannot be edited, only corrected with new
transfers. Every committed change is published to Kafka, and a reconciliation check proves at
any time that debits equal credits.

"Our customers do not forgive a wrong balance, so the ledger had to be boring and provable,"
said the (fictional) head of engineering at ledger-core. "Idempotency, immutability and
reconciliation are not features we added; they are the design."

"We replaced a hand-rolled balance table in a week, and the first retry storm after launch
cost us zero duplicate payments," said a (fictional) platform engineer at a marketplace
start-up.

To get started, clone the repository, run `docker compose up`, start the API and open the
dashboard. The README walks through creating two accounts and moving money in under ten
minutes.

## 2. FAQ

### External (customer) FAQ

**Q1. What does it do that a balance column does not?**
It enforces double entry, makes writes idempotent, keeps an immutable history, supports
two-phase payments and publishes every change as an event. A balance column does none of
these.

**Q2. How are retries handled?**
You choose the transfer id (a UUID). Sending the same id with the same body again returns
the stored result with HTTP 200 and moves no money. The same id with a different body
returns 409.

**Q3. How do I represent a card authorisation or an escrow hold?**
Create a pending transfer. It reserves funds in the pending counters. Later post it in full
or in part, void it, or let it expire.

**Q4. How do I know the numbers are right?**
`GET /reconciliation` recomputes every balance from the transfer history and checks that
debits equal credits per ledger. A background job runs the same check every 5 minutes.

**Q5. How do other systems learn about transfers?**
Each committed transfer is published at least once to Kafka as a CloudEvent. Consumers
dedupe on the event id.

**Q6. Can I run it in production?**
Not yet. v1 is for local use and has no authentication or rate limiting. Both are planned
before any public deployment.

### Internal (stakeholder) FAQ

**Q1. What does it cost?**
No cash cost: free tiers of GitHub Actions, local Docker and open-source software. The real
cost is about 55 story points of build time across three sprints (see the Delivery Plan).

**Q2. What are the main risks?**
A concurrency bug that corrupts balances, undetected contract drift between API and web, and
being mistaken for a compliant product. Mitigations: ordered locking with concurrency and
property tests, a single API contract, and an explicit compliance statement (BRD NFR-6).

**Q3. Why now?**
Retries, outbox and reconciliation are the topics fintech interviewers probe most, and an
Angular and Spring portfolio with real ledger correctness is rare. The TigerBeetle model gives
a well-known, citable design.

**Q4. Why us?**
The owner works in finance on Java, Spring, Kafka and Angular, so the stack matches daily
practice and the demo is credible to the people reviewing it.

**Q5. What are we NOT building?**
Authentication, rate limiting, multi-currency FX, n-leg journal entries, a ledger DSL,
real bank or card connections, multi-node deployment and public hosting.

**Q6. How do we know it is working?**
Reconciliation stays balanced after a load test, the property and concurrency suites pass in
CI, and a stranger can run the demo from the README alone. Thresholds are in section 6.

## 3. Target customer and job-to-be-done

**Target customer:** the engineering lead at a seed-stage fintech or marketplace (2 to 15
engineers) who has to track balances for wallets, sellers or lenders and cannot afford a
duplicate-payment or missing-money incident. For the portfolio, the second target is the
hiring manager reviewing the repository.

**Job to be done:** "When money moves between my users, my platform and my partners, help me
record it so it is exact, safe to retry, explainable to an auditor and visible to other
systems, so I can ship payments features without fearing the books."

## 4. Riskiest assumptions and hypotheses

| Hypothesis | How the MVP tests it | Pass / fail threshold |
|---|---|---|
| H1. Postgres with ordered row locking is correct and fast enough for a credible ledger | Concurrency test and load test on a laptop | Exact balances with 32 writers; p95 under 50 ms; 200 transfers per second sustained; pass only if all three hold |
| H2. Client-chosen ids make retries safe without a separate idempotency store | Property tests replaying random sequences | 100% of replays leave balances and event count unchanged |
| H3. A transactional outbox gives no lost or phantom events | Embedded-Kafka tests with forced failures | Zero lost events; duplicates share a CloudEvents id |
| H4. Reconciliation can prove correctness continuously, not just at month end | Load test then corrupt one counter | Clean run reports balanced; corrupted run lists exactly the corrupted account |
| H5. Developers can adopt it quickly | Time a fresh clone following the README | Money moved in under 10 minutes by someone who has not seen the repo |
| H6. The live feed makes the design tangible to non-engineers | Demo to two reviewers (for example former colleagues) | Both can explain what happened on screen without help |

## 5. MVP scope

### Must-have features (mapped to the BRD)

| Feature | BRD requirement |
|---|---|
| Create accounts with flags | FR-1 |
| Single transfers with exact validation order and result codes | FR-2, FR-3 |
| Balance-limit enforcement | FR-4 |
| Idempotent writes | FR-5 |
| Pending, post (full or partial), void and expiry | FR-6, FR-7, FR-8 |
| Linked batches | FR-9 |
| Immutable, ordered transfer history | FR-10, FR-11 |
| Outbox and Kafka CloudEvents | FR-12, FR-13 |
| Reconciliation endpoint and job | FR-14 |
| History queries with keyset pagination | FR-15 |
| Live activity over SSE | FR-16 |
| Dashboard | FR-17 |
| OpenAPI and problem details | FR-18 |

### Explicitly cut

| Cut | Why |
|---|---|
| Authentication and rate limiting | Needed only for public hosting; planned for phase 2 |
| FX and cross-ledger transfers | Adds rate and rounding rules that dilute the core story |
| N-leg journal entries and a DSL | Linked single-leg transfers cover the cases with simpler invariants |
| Webhooks and a customer-facing SDK | Kafka and REST are enough to prove the design |
| Multi-tenant org model | One operator in the MVP |
| Public deployment | Unauthenticated API must stay on localhost |

### MVP user journey

1. A developer clones the repo and starts PostgreSQL and Kafka with `docker compose up`.
2. They start the API with the `dev` profile and open the dashboard.
3. They create two accounts, one with `debitsMustNotExceedCredits`.
4. They fund the first account with a transfer from a settlement account.
5. They send the same transfer again and see the original result with no balance change.
6. They create a pending transfer, watch the pending counters rise, then post it in part.
7. They submit a linked batch where one item fails and see the whole chain roll back.
8. They watch every step appear in the live feed, then open the reconciliation view and see
   the books balanced.

## 6. Success and exit criteria

**Launch criteria (quality bar):**

- All must-have features done; every API.md result code has a test.
- CI green; coverage gates 95% (domain) and 90% (service); no skipped tests.
- Reconciliation balanced after the load test of at least 100,000 transfers.
- README with screenshots and a demo GIF; no secrets or personal data in the repository.

**Metrics and targets:**

| Metric | Target |
|---|---|
| Time to first transfer from a fresh clone | Under 10 minutes |
| Transfer p95 latency (laptop, local PostgreSQL) | Under 50 ms |
| Event to feed latency p95 | Under 2 s |
| Duplicate or lost events in tests | 0 |
| Reconciliation mismatches after load test | 0 |

**Decision rule:**

- **Persevere** (start phase 2: auth, rate limiting, public demo) if every launch criterion
  and at least five of six hypotheses pass.
- **Pivot** (for example to a smaller embeddable library, or a different storage engine) if
  H1 fails but the rest pass: the model is sound, the engine is not.
- **Stop** (archive with a write-up of what was learned) if H2, H3 or H4 fails after one
  fix cycle: the core correctness claims did not hold.

## 7. MVP timeline

The MVP is delivered by Sprints 1 to 3 in [PROJECT_PLAN.md](PROJECT_PLAN.md):

| Sprint | Delivers | Milestone |
|---|---|---|
| 1. Money moves correctly | Backend core: FR-1 to FR-11, FR-18 | Internal build |
| 2. See it live | Outbox, Kafka, projector, SSE, reconciliation, dashboard: FR-12 to FR-17 | v0.1 demo |
| 3. Trust it | CI fixes, end-to-end run, load test, screenshots | v1.0 portfolio-ready (tag v0.1.0 at gate G4) |
