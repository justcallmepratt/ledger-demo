# ledger-core web dashboard

Angular dashboard for the `ledger-core` double-entry ledger. It talks to the HTTP and SSE
contract in [`../docs/API.md`](../docs/API.md) and nothing else.

- Angular 22.3 (standalone components, signals, `@if` / `@for`, `OnPush` everywhere, typed
  reactive forms, functional interceptors, lazy routes), TypeScript 6 strict, strict templates
- Unit tests: the Angular CLI's Vitest runner with jsdom; lint: angular-eslint
- Package manager: pnpm, exact dependency versions, lockfile committed

## Run

```bash
pnpm install --frozen-lockfile
pnpm start            # ng serve on http://localhost:4200
```

`proxy.conf.json` forwards `/api` (REST and the SSE stream) to `http://localhost:8080`, where the
backend listens. No CORS setup is needed in development.

## Check

```bash
pnpm lint             # ESLint, including template accessibility rules
pnpm test             # unit and accessibility tests, no backend needed (all HTTP is mocked)
pnpm build            # production build to dist/web
pnpm format:check     # Prettier
```

## Screens

| Route | What |
|---|---|
| `/` | Dashboard: ledger health from `GET /reconciliation`, accounts table with keyset "Load more", live ticker, "New account" dialog |
| `/accounts/:id` | Counters, flags, transfers touching the account (direction relative to it, paginated), balance history when the `history` flag is set |
| `/transfers/new` | Transfer form with same-ledger account filtering and an optional two-phase hold |
| `/transfers/pending` | Pending holds with a live countdown, Post (full or partial) and Void |
| `/activity` | Live feed: `GET /activity`, then SSE `activity` events, newest first, capped at 200 |
| `**` | 404 |

## How it is built

```
src/app/
  api/        DTOs (models.ts), one service per resource, ApiProblem, interceptors,
              result-code messages, Paginator, LedgerStore
  core/       money.ts, time.ts, ids.ts, ActivityFeed (SSE), NoticeService (toasts)
  shared/     HudPanel, Dialog, Field, NoticeRegion, FlagChips, ProblemText
  features/   dashboard, accounts, transfers, pending, activity, not-found
src/styles/   _tokens.scss (design tokens), _base.scss, _components.scss
src/testing/  test helpers (HTTP providers, fake EventSource, axe runner, DOM helpers)
```

### Money

Amounts and counters are decimal integer strings in minor units. They are parsed with `BigInt` and
never pass through `Number` or `parseFloat` (`src/app/core/money.ts`).

- `formatMinor("1050", 2, "USD")` gives `$10.50`; negatives get a leading minus.
- `parseMajor("10.5", 2)` gives `"1050"`. It rejects empty input, non-digits, more decimals than the
  asset scale, zero (unless `allowZero`, used for "post the full amount") and anything above
  `9223372036854775807`.
- Net balance = credits posted − debits posted, with pending shown separately. The UI explains the
  debit and credit sides next to the numbers.

### Idempotency

- A transfer's UUID (`crypto.randomUUID`) is generated when the form opens and reused for every retry,
  including after network errors and 5xx. It is regenerated only after a success (201 or 200 replay)
  or when the user presses "New transfer". The id is shown on screen.
- New-account ids follow the same rule per dialog; a 409 offers "Use a new id".
- Post and Void each send a new transfer with their own UUID plus `pendingId`. The id is kept per
  (pending transfer, action, amount) so a retry of the same input reuses it.

### Errors

The error interceptor turns every failed call into an `ApiProblem` (`status`, `code`, `title`,
`detail`, `errors[]`, `retryable`). `result-codes.ts` maps every code in API.md (and the
`pending_transfer_has_different_<field>` and `exists_with_different_<field>` families) to a sentence;
the raw code is shown next to it in monospace.

### Live feed

`ActivityFeed` is provided per page and closes with it. It loads `GET /activity`, opens
`EventSource('/api/v1/activity/stream')`, merges `activity` events (deduplicated by `eventId`,
newest first, capped at 200) and reconnects with exponential backoff (1 s to 30 s) when the browser
gives up. After a manual reconnect it refills the gap from the REST feed.

## Design notes

The look follows the owner's personal site: a Cyberpunk 2077-inspired HUD (chamfered panels, left
accent stripe, outline buttons, tracked uppercase labels, red grid on pure black). Fonts are
self-hosted through `@fontsource` (Rajdhani for UI, Orbitron for display, Share Tech Mono for
numbers); nothing loads from a font CDN. No game logos, images or names are used. The footer carries
the line "Visual style inspired by the Cyberpunk 2077 UI. Unofficial fan design."

- **Tokens** live in `src/styles/_tokens.scss` as CSS custom properties. `--cp-red` (#FF2E57) is the
  text-safe red; `--cp-red-deep` (#C5003C) is for fills, borders and glows only.
- **Contrast** (WCAG 2.1 AA): every text colour is at least 4.5:1 on black, the panel colour and the
  HUD surface; control outlines and the focus ring are at least 3:1. `src/styles/tokens.spec.ts`
  asserts these ratios from the token file. Red is the primary identity colour, not an error colour:
  errors and warnings use yellow plus a word ("Error", "Warning"), never colour alone.
- **Keyboard and focus**: skip link, a white focus ring on everything, buttons and links at least
  44 px high, a focus-trapping dialog (Escape closes, focus returns to the opener), scrollable tables
  are focusable regions.
- **Semantics**: tables have captions and `scope`d headers; every field has a `<label>`, hint and
  error text tied with `aria-describedby`, and `aria-invalid` on errors; the activity feed, the
  transfer result, the ticker and the toasts are `aria-live="polite"` regions.
- **Motion**: glitch headings and the moving scanline band stop under `prefers-reduced-motion:
  reduce` (the static scanline texture stays); transitions and entrance effects are removed too.
- **Accessibility tests**: each routed page component, the dialogs and the shell are checked with
  axe-core. jsdom has no layout, so axe's colour-contrast rule cannot run there; contrast is covered
  by the token test above.

## Interpretations of the API contract

- Paged endpoints other than `GET /activity` return `{ items, nextCursor }`; the API text says
  "page of Transfer" and "page of `{...}`" for the account sub-resources, and this is assumed to be
  the same shape as `GET /accounts`. `GET /activity` returns a bare array as documented.
- Post and Void send `amount: "0"` for a void and for a full post (API.md: amount may be 0 for
  post/void, and "omitted or 0 = full amount"), and the explicit minor amount for a partial post.
  Accounts, ledger and code are omitted so the server inherits them.
- A two-phase transfer's `timeoutSeconds` of 0 is treated as "no expiry".
- `code` on transfers is limited to 1 to 65535, like the account code (API.md gives no range).
- Activity items are ordered by `occurredAt`, then `seq`, because `seq` is documented for transfers
  and may not be comparable with account events.
