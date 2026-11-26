# Business Plan: ledger-core (hypothetical)

This is a hypothetical business case for a portfolio project. ledger-core is not a company
and holds no real money. The plan frames it as **ledger-as-a-service for seed-stage
fintechs and marketplaces** to show product and commercial thinking. All market sizes,
prices and financials below are labelled assumptions or bottom-up arithmetic, not forecasts.
Companion documents: [BRD.md](BRD.md), [MVP.md](MVP.md), [TECH_STACK.md](TECH_STACK.md),
[PROJECT_PLAN.md](PROJECT_PLAN.md).

## 1. Executive summary

ledger-core is a hosted double-entry ledger and payments API: exact integer money, retry-safe
writes, authorise-then-capture transfers, an immutable history, a Kafka event feed and a
continuous proof that the books balance. It targets seed-stage fintechs and marketplaces
that need a trustworthy ledger but cannot yet justify building one or buying an
enterprise payments-operations platform.

- **Problem:** hand-built balance tables cause duplicate payments, race conditions and
  reconciliation breaks.
- **Product:** the TigerBeetle accounting model on PostgreSQL and Kafka, delivered as an API.
- **Model:** usage-tiered subscription with a free developer tier; self-host option.
- **Hypothetical outcome:** about 120 paying customers and 1.0 million dollars of revenue by
  year 3, with about 1.9 million dollars of cumulative funding needed to get there.

## 2. Problem and opportunity

Every product that holds a balance needs a ledger, and early teams usually build it
themselves. Three failure modes recur: retries that create duplicate payments
([Stripe on idempotency keys](https://stripe.dev/blog/because-nobody-likes-being-charged-twice)),
movements that are not recorded immutably with a clear source and destination
([Modern Treasury on ledger design](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-i)),
and reconciliation work done by hand; PYMNTS puts industry losses from manual billing and
reconciliation inefficiency at about 98.5 million dollars a year
([PYMNTS, 2025](https://www.pymnts.com/news/banking/2025/manual-billing-costs-banks-98-5-million-every-year)),
an estimate for the whole industry, not one firm.

The opportunity is the gap between a spreadsheet-grade balance table and an enterprise
platform. Open-source ledgers are strong but ask the customer to run and secure them;
enterprise platforms are priced through sales conversations aimed at larger firms.

## 3. Market analysis

There is no sourced market-size figure for "ledger infrastructure" that I can cite with
confidence, so the table is **bottom-up arithmetic from stated assumptions**. Change the
assumptions and the numbers change.

| Level | Definition | Assumption | Count | Revenue at 8,400 dollars per customer per year |
|---|---|---|---|---|
| TAM | US seed to Series A fintechs and marketplaces that hold customer balances | 6,000 companies (assumption) | 6,000 | 50.4 million dollars |
| SAM | The share that build on a JVM or PostgreSQL stack, want an API rather than self-hosting, and do not already use a bank-provided ledger | 20% of TAM (assumption) | 1,200 | 10.08 million dollars |
| SOM (year 3) | Share of SAM won in three years by a developer-led product | 10% of SAM (assumption) | 120 | 1.008 million dollars |

The revenue per customer of 8,400 dollars is 700 dollars per month, the assumed year-3
average (section 6).

**Trends:**

- Companies increasingly treat the ledger as a separate system of record. Modern Treasury,
  Formance and Blnk all sell or publish it as standalone infrastructure.
- Event-driven integration: ledgers are expected to emit changes for fraud, notifications and
  reporting, which is why ledger-core publishes CloudEvents to Kafka.
- Idempotent, immutable design is now the baseline expectation, not a differentiator.

**Target segments:** wallets and stored-value apps; marketplaces paying out sellers or
creators; lending and buy-now-pay-later start-ups; embedded-finance platforms.

## 4. Competitive landscape

Pricing models were checked against each vendor's own pages in November 2026. Where a page
lists no price, it is marked "not published".

| Competitor | Offering | Pricing model | Strengths | Gaps |
|---|---|---|---|---|
| [Modern Treasury](https://moderntreasury.com/pricing) (Ledgers and Payments) | Ledger plus payment operations across ACH, wires and other rails | Usage-based and annual terms; no prices or tiers published for Ledgers, "talk to us" | Full payments operations, strong brand, reconciliation | Sales-led, aimed at larger firms; not open source |
| [Formance](https://website-v2-azure.vercel.app/pricing) | Open-source ledger with a Numscript DSL, payments, wallets, reconciliation | Free sandbox; production by contact; states "no consumption-based pricing"; cloud and self-managed options | Programmable money, flexible deployment | DSL to learn; production prices not published |
| [Blnk](https://github.com/blnkfinance/blnk) | Open-source (Apache-2.0, about 560 GitHub stars) double-entry ledger | Free self-host; Blnk Cloud sandbox; paid support plans, prices not listed in the repository | Open source, simple concepts, reconciliation | Smaller community; managed offering still young |
| [TigerBeetle](https://tigerbeetle.com/cloud) | Purpose-built financial transactions database | TigerBeetle Cloud: flat monthly fee per cluster, billed yearly: 2,048 dollars (Startup), 8,192 (Growth), 32,768 (Enterprise), plus overage per million records and lookups. Self-host is free | Extreme throughput and correctness, 99.99% SLA on cloud | A database, not an application API: no REST layer, event feed or dashboard; high floor price for a seed-stage team |
| [Stripe Treasury / Financial Accounts](https://support.stripe.com/questions/treasury-pricing-and-fees) | Embedded bank accounts for platforms, with ledgering inside Stripe | No fee for holding funds; fees on payouts, currency conversion and issuing; amounts on separate pages | Real regulated accounts and rails, one vendor | Only for money held in Stripe's accounts; a ledger for any other use case is not offered |
| Build in-house | Balance table or custom service | Engineer time | Total control | Duplicate payments, races and reconciliation work fall on the team |

**Our differentiation:** a complete, application-level ledger (REST, SSE, Kafka, dashboard,
reconciliation) with TigerBeetle's proven model, on a stack small teams already run
(PostgreSQL, Kafka, Spring), at a price a seed-stage team can approve without a sales call.
The honest trade-off: PostgreSQL will not match TigerBeetle's raw throughput, and a hosted
ledger would need to earn the trust that the incumbents already have.

## 5. Value proposition and positioning

**Positioning statement:** For seed-stage fintech and marketplace engineering teams who need
exact, auditable balances without building a ledger, ledger-core is a ledger-as-a-service API
that makes retries safe, history immutable and the books provably balanced. Unlike
Modern Treasury, which is sold through sales conversations to larger firms, or TigerBeetle,
which is a database you must wrap yourself, we ship a complete, transparently priced ledger
API with a live event feed that a small team can adopt in an afternoon.

## 6. Business model

**Revenue streams:** monthly subscription by volume tier (about 85% of revenue);
self-host support plan; paid onboarding and design review for larger customers.

**Pricing tiers (assumptions):**

| Tier | Price per month | Included | Overage |
|---|---|---|---|
| Developer | 0 | Sandbox, 100,000 transfers per month, community support | None; throttled |
| Startup | 299 dollars | 2 million transfers per month, 10 ledgers, email support | 0.15 dollars per 1,000 transfers |
| Growth | 1,499 dollars | 20 million transfers per month, SSO, audit export, priority support | 0.10 dollars per 1,000 transfers |
| Scale | from 5,000 dollars | Dedicated cluster, SLA, custom terms | Negotiated |

For comparison, TigerBeetle Cloud starts at 2,048 dollars per cluster per month; the
Startup tier here is deliberately a fraction of that because the offer is an application
API on a shared PostgreSQL service, not a dedicated database cluster.

**Unit-economics assumptions (all assumed, not measured):**

| Metric | Assumption | Derivation |
|---|---|---|
| Average revenue per customer | 650 dollars per month (year 2 mix) | Blend of Startup and Growth tiers |
| Gross margin | 78% (year 2) | Hosting and support are 22% of revenue |
| Monthly churn | 2% | Customer lifetime about 50 months |
| CAC | 3,000 dollars | Developer-led: content, community, occasional demo call |
| LTV | 25,350 dollars | 650 x 0.78 / 0.02 |
| LTV to CAC | about 8.4 | 25,350 / 3,000 |

## 7. Go-to-market

- **Channels:** open-source repository and a self-hostable build; technical write-ups on the
  design (idempotency, outbox, reconciliation); a public sandbox; Hacker News and fintech
  engineering communities; conference lightning talks.
- **Launch sequence:** (1) v0.1.0 open-source release with the live dashboard; (2) public
  sandbox after authentication and rate limiting land; (3) paid Startup tier with five design
  partners at a discount; (4) Growth tier once the first audit export and SSO ship.
- **Partnerships:** sponsor-banks and banking-as-a-service providers who need a ledger for
  their programme managers; payments processors as integration partners; accounting
  tools through the event feed.
- **Developer relations:** quick-start under 10 minutes, a Postman collection, client
  examples in Java, TypeScript and Python, a public changelog, and a "break the ledger"
  challenge that rewards found invariant violations.

## 8. Operations and technology

**Team needed (year 1):** founder-engineer (ledger core), a second backend engineer
(platform and reliability), a developer advocate half-time, and fractional finance and
legal advice.

**Infrastructure cost per month (estimates for a managed PostgreSQL and Kafka footprint):**

| Scale | Customers | Estimate | What it covers |
|---|---|---|---|
| Small | 10 | 1,500 dollars | One region, small PostgreSQL with replica, single-broker-class Kafka, monitoring |
| Medium | 60 | 9,000 dollars | Larger PostgreSQL, three-broker Kafka, backups, staging environment |
| Large | 180 | 28,000 dollars | Sharded or multiple PostgreSQL clusters, multi-AZ Kafka, on-call tooling |

**Security and compliance if this were real:**

- SOC 2 Type II within 12 to 18 months; customers will ask for it. TigerBeetle Cloud
  advertises SOC 1 and SOC 2 Type II, so this is the expected bar.
- PCI DSS: avoid scope by never storing card numbers; the ledger stores references only.
- Money transmission: a ledger that does not hold or move funds is generally outside
  state money-transmitter licensing, a position Formance states about its own product
  ("it doesn't sit in the flow of funds"). Legal counsel must confirm before launch, and any
  feature that holds customer funds changes the answer.
- SEC and FINRA are not triggered by a pure ledger, but customers in securities need
  immutable audit trails, which the design provides.
- Data protection: encryption in transit and at rest, per-tenant isolation, audit logs of
  every write, a retention and deletion policy compatible with an immutable history.

## 9. Financial projections (hypothetical, 3 years, US dollars)

| | Year 1 | Year 2 | Year 3 |
|---|---|---|---|
| Average paying customers | 6 | 36 | 120 |
| Average revenue per customer per month | 600 | 650 | 700 |
| Revenue | 43,200 | 280,800 | 1,008,000 |
| Cost of revenue (hosting, support) | 10,800 | 61,776 | 201,600 |
| Gross profit | 32,400 | 219,024 | 806,400 |
| Gross margin | 75% | 78% | 80% |
| Operating expenses (people, marketing, compliance) | 420,000 | 900,000 | 1,600,000 |
| EBITDA | -387,600 | -680,976 | -793,600 |

Cumulative EBITDA over three years is about -1.86 million dollars, so the plan assumes a
seed round of about 2 million dollars.

**Assumptions:** customer counts are the average paying customers in each year; year 3 equals
the SOM in section 3 (10% of SAM); revenue = customers x average monthly revenue x 12;
gross margin per section 6; operating expenses assume 3 people in year 1, 6 in year 2 and 10
in year 3 at a loaded cost of about 140,000 dollars each, with SOC 2 and legal costs inside
year 2 and 3; no FX, no tax, no financing costs. The plan is not break-even within three
years; that is a deliberate, stated outcome of the assumptions.

## 10. Risks and mitigations

| Type | Risk | Mitigation |
|---|---|---|
| Market | Open-source alternatives (Blnk, Formance, TigerBeetle) are free to self-host | Compete on hosted convenience, a complete API and transparent pricing; keep a self-host option |
| Market | Seed-stage teams build in-house or use Stripe | Time-to-first-transfer under 10 minutes; migration guides |
| Regulatory | Holding funds would trigger licensing | Never hold funds; ledger records only; legal review before launch |
| Technical | Balance corruption or lost events | Ordered locking, property tests, outbox, continuous reconciliation, public invariants |
| Technical | PostgreSQL throughput ceiling | Partition by ledger; document limits; keep storage behind an interface so an engine such as TigerBeetle can be swapped in |
| Operational | Customer trust without a track record | Open-source core, public test suite, design-partner references, SOC 2 |
| Operational | Key-person dependency | Documentation-first culture; ADRs; second engineer hired in year 1 |

## 11. KPIs and OKRs (year 1)

| Objective | Key results |
|---|---|
| O1. Prove the core is trustworthy | Zero ledger-correctness incidents; reconciliation balanced 100% of runs; 95% domain and 90% service test coverage maintained |
| O2. Make adoption effortless | Time to first transfer under 10 minutes; 300 sandbox sign-ups; 5 design partners in production |
| O3. Show a viable business | 12 paying customers at year-end; 600 dollars average monthly revenue per customer; monthly churn at or below 2% |

## 12. Roadmap

| Horizon | Items |
|---|---|
| Now | MVP: accounts, transfers, two-phase, linked batches, outbox, reconciliation, dashboard (see MVP.md) |
| Next | OAuth2/JWT and per-client rate limits; audit logging; public sandbox; Debezium change capture; client SDKs |
| Later | Multi-tenant organisations; multi-currency and FX; webhooks; usage metering and billing; SOC 2; storage-engine option for very high volume |

## 13. Portfolio angle

- Shows production-shaped fintech engineering: exact money, idempotency, two-phase
  transfers, transactional outbox and continuous reconciliation, all tested with property
  and concurrency tests.
- Shows product thinking: working-backwards MVP, explicit hypotheses with pass or fail
  thresholds, and an honest competitive and financial analysis rather than a pitch.
- Shows delivery discipline: frozen requirements, phase gates, CI with coverage gates, and
  documentation kept in step with the code.
