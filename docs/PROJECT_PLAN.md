# Project plan

`ledger-core` uses a hybrid delivery method:

- **Waterfall up front** for the parts that are expensive to change later: requirements,
  the accounting model, the API contract and the data model. These were written and
  frozen before any code (phase gate G1). Changing them later needs an ADR in
  [ARCHITECTURE.md](ARCHITECTURE.md).
- **Scrum inside the build**: short sprints with a sprint goal, a prioritised backlog,
  a definition of done, a review against the contract and a retrospective.
- **Phase gates** between stages, so each stage starts from verified work.

## Roles

| Role | Who |
|---|---|
| Product owner | Prat (accepts sprint reviews, owns priorities) |
| Architect / scrum master | Claude (Opus): design, backlog, reviews, unblocking |
| Developers | Claude (Sonnet) agents: one per area (backend, web) |

## Phases and gates

| Phase | Method | Exit gate |
|---|---|---|
| 0. Requirements and design | Waterfall | **G1** ARCHITECTURE.md, API.md and rules reviewed and committed ✅ |
| 1. Build: Sprints 1–2 | Scrum | **G2** all backlog items for phase 1 done; CI green on every PR |
| 2. Hardening: Sprint 3 | Scrum | **G3** end-to-end run locally (compose + API + web), reconciliation balanced after a load test |
| 3. Release | Waterfall | **G4** v0.1.0 tag, README screenshots, portfolio write-up on the personal site |
| 4. Phase 2 features | Scrum | Auth (OAuth2/JWT), rate limiting, then public demo |

## Definition of done

- Behaviour matches ARCHITECTURE.md rules and API.md exactly (or the doc was updated in
  the same PR with a reason).
- Tests cover the change; coverage gates pass; no skipped tests.
- `mvn -B verify` and `pnpm lint && pnpm test && pnpm build` pass in CI.
- No secrets, emails or phone numbers; dependencies pinned and on mature lines.
- Reviewed by the architect against the contract before merge.

## Sprint 1 — "Money moves correctly" (backend core)

Goal: a running API that creates accounts and transfers with every ledger rule enforced.

| # | Item | Points |
|---|---|---|
| 1.1 | Domain model and transfer engine with all result codes | 5 |
| 1.2 | Property tests for double-entry, limits and idempotent replay | 3 |
| 1.3 | Flyway schema, immutability trigger, seed ledger | 3 |
| 1.4 | JDBC repositories with ordered locking | 3 |
| 1.5 | Account and transfer services, linked chains, two-phase | 8 |
| 1.6 | REST API, problem details, OpenAPI | 5 |
| 1.7 | Integration tests incl. concurrency | 5 |

## Sprint 2 — "See it live" (events and dashboard)

Goal: every transfer appears on the dashboard in real time and the books prove balanced.

| # | Item | Points |
|---|---|---|
| 2.1 | Outbox publisher to Kafka, CloudEvents | 5 |
| 2.2 | Activity projector, SSE stream with replay | 3 |
| 2.3 | Expiry and reconciliation jobs, metrics | 3 |
| 2.4 | Angular shell, design tokens, accessibility baseline | 3 |
| 2.5 | Dashboard, account detail, transfer form, pending, live feed | 8 |
| 2.6 | Web tests incl. money parsing and axe checks | 3 |

The two agents build sprints 1 and 2 in parallel because the frozen API contract removes
the dependency between backend and web.

## Sprint 3 — "Trust it" (hardening)

| # | Item | Points |
|---|---|---|
| 3.1 | Fix everything CI finds on first run of the backend | 3 |
| 3.2 | Local end-to-end run against compose; fix contract drift | 3 |
| 3.3 | Load script (e.g. k6 or a JUnit load test) + reconciliation check | 3 |
| 3.4 | README screenshots and a short demo GIF | 2 |
| 3.5 | Security pass against SECURITY.md scope | 2 |

## Risks

| Risk | Mitigation |
|---|---|
| Backend cannot be compiled in the build workspace (Maven Central blocked there) | Agent mirrors a known-good Boot 4 project; uncertain versions flagged; CI is the compiler; sprint 3.1 reserved for fixes |
| Contract drift between backend and web built in parallel | Single API.md; architect review of both against it before merge |
| Spring Boot 4 / Angular 22 API differences from older versions | Copy patterns from the owner's working repos; read framework docs in node_modules |

## Log

- 2026-11-26 — G1 passed: architecture, API contract and rules committed. Sprints 1 and 2
  started with two developer agents. CI, Dependabot and compose prepared.
