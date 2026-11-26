# Agent instructions

`ledger-core` is a double-entry ledger with a payments API, a Kafka change feed and an
Angular dashboard. It is project 1 of the owner's Fintech Projects Wiki and a public
portfolio piece. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is the design and
[docs/API.md](docs/API.md) is the HTTP and event contract. Both are binding: if code
and docs disagree, fix the code or update the doc in the same change, never leave them
out of step.

## Layout

| Path | What | Stack |
|------|------|-------|
| `backend/ledger-domain/` | Pure domain model and rules. No Spring, no I/O | Java 21 |
| `backend/ledger-service/` | The API, persistence, outbox, Kafka, jobs | Spring Boot 4.1.x, Maven |
| `web/` | Dashboard | Angular (latest major at least 3 months old), pnpm |
| `docs/` | Architecture, ADRs, API contract | Markdown |

## Checks

CI (`.github/workflows/ci.yml`) runs these. Run the matching set before you commit:

```bash
# backend/ — unit, property, slice and integration tests (embedded PostgreSQL + embedded Kafka), coverage gate
mvn -B verify
# web/
pnpm install --frozen-lockfile && pnpm lint && pnpm test && pnpm build
```

## Rules

- **The repo is public.** Never add an email address, phone number or home address to
  code, docs, test data or commits.
- **Money is never floating point.** Amounts are integers in minor units (`long` in Java,
  `BIGINT` in SQL, decimal strings over JSON, `bigint` in TypeScript). Arithmetic that can
  overflow uses `Math.addExact` / `Math.subtractExact`.
- **The ledger is append-only.** Transfers are never updated or deleted. Corrections are
  new transfers. The only mutable rows are account balance counters, pending-transfer
  state and outbox publish markers.
- **Commits:** author them as Pratham Sachan with the GitHub no-reply address. One small,
  focused change per branch, branched from `main`. Never push to `main` directly.
- **Dependencies:** prefer LTS lines (Java 21 Corretto, Spring Boot, Node 22). Non-LTS
  libraries and new majors wait about three months after release. Pin exact versions.
- **Tests:** every fix or feature comes with tests. Ledger invariants get property tests.
  Backend coverage gate: 95% instructions on `ledger-domain`, 90% on `ledger-service`.
- **Secrets:** none in the repo. `.env.example` and `docker-compose.yml` carry variable
  names only; compose fails fast if a required variable is unset.
- **Time:** store and compare in UTC (`timestamptz`, `Instant`). Inject a `Clock`; never
  call `Instant.now()` directly in business code.
- **Design:** the web app follows the owner's CP2077 design system (tokens in
  `web/src/styles/_tokens.scss`, taken from the personal site) and WCAG 2.1 AA.
