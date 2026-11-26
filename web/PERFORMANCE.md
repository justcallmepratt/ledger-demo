# Web performance

Production build of the dashboard, served by `perf/mock-server.mjs` (deterministic data: 500
accounts paged by 50, 2,000 activity items, SSE at about 20 events/s, gzip/brotli like a real host).
Chrome 153 headless, Lighthouse 13 via `@lhci/cli` 0.16.1, 2 vCPU box.

## Method

| What | How |
|---|---|
| Lighthouse | `pnpm build && pnpm perf:lhci` (5 runs x `/`, `/accounts/:id`, `/transfers/new`, `/activity`, desktop and mobile presets, simulated throttling). Reported as median / worst run. TBT is the INP proxy. |
| Interactions | `pnpm perf:interactions` (Chrome DevTools Protocol over Node's built-in WebSocket, no extra dependency): p95 of route navigation, SSE event to row painted (1,000 events at 20/s), and "Load more" (50 rows). Time is click or event arrival until the next completed frame. |
| Assertions | `perf/thresholds.cjs` (per URL, median run), enforced by `.github/workflows/web-perf.yml`; Angular budgets in `angular.json`. |

Interaction metrics move by about +/-30% between identical runs on this box, so they are reported
but not gated.

## Baseline vs final (median / worst run)

| Preset | URL | Score | CLS | LCP ms | TBT ms | kB |
|---|---|---|---|---|---|---|
| desktop | `/` | 75 / 74 -> **82 / 80** | 1.344 / 1.394 -> **0.363 / 0.371** | 726 -> 725 | 13 -> 13 | 211 |
| desktop | `/accounts/:id` | 75 / 75 -> **97 / 96** | 0.967 -> **0.108** | 711 -> 706 | 16 -> 22 | 173 |
| desktop | `/activity` | 75 / 75 -> 76 / 75 | 1.807 -> **0.865** | 679 -> 671 | 8 -> 10 | 185 |
| desktop | `/transfers/new` | 100 / 100 | 0.001 | 689 -> 676 | 21 -> 18 | 203 |
| mobile | `/` | 65 / 61 -> **82 / 79** | 0.353 -> **0.092** | 3124 -> 3122 | 495 -> 421 | 210 |
| mobile | `/accounts/:id` | 88 / 80 -> 89 / 87 | 0.066 -> **0.002** | 2883 -> 2852 | 299 -> 308 | 173 |
| mobile | `/activity` | 72 / 64 -> 65 / 58 | 1.996 -> 1.886 | 632 -> 2854 * | 203 -> 272 | 185 |
| mobile | `/transfers/new` | 85 / 82 -> 82 / 77 | 0.077 -> **0.002** | 623 -> 3063 * | 563 -> 469 | 209 |

\* The baseline LCP was the footer: before the route chunk rendered, the footer sat in the viewport
and counted as the largest paint at about 620 ms. With the footer below the fold the LCP is the real
content (about 3 s on simulated slow 4G with 4x CPU). The metric got more honest, not slower; the
mobile score on those two pages dropped a few points because of it.

Interactions (p95 ms, baseline -> final; noise is large): navigation activity 92 -> 66, transfers 119 -> 93,
dashboard 103 -> 86, account 83 -> 88; "Load more" 75 -> 85 (last page 116 -> 85); SSE to row, steady 32 -> 33,
burst of 1,000 at 500/s 79 -> 128 (identical code path, so this is noise); long tasks > 50 ms during
the SSE runs: none at 20/s.

## Hypotheses

1. **Kept: stop the page-wide layout shift.** The 0.97 shift was the footer, visible at the bottom of
   the viewport while the route chunk loaded and the table was empty, then pushed thousands of pixels
   away. Fix: footer starts below the fold (`.shell__main { min-height: 100vh }`), loading states reserve a
   screen (`.state--loading`), and the dashboard ticker reserves its six rows. Desktop CLS 1.3 -> 0.36,
   score +7 to +22 points. No test or axe changes needed.
2. **Rejected: batch SSE updates per animation frame** (first event at once, the rest merged once per
   frame). Burst p50 rose from 27 to 42 ms and p95 from 79 to 168 ms while the long-task count did not
   improve; the feed is capped at 200 rows, so a render per event is cheap. Reverted.
3. **Not attempted (out of scope for a demo):** font subsetting/preload and deferring the scanline
   CSS (LCP is script-bound on mobile, fonts are about 75 kB), virtual scrolling (200 rows is cheap).

## Budgets

`angular.json` (production): initial 320 kB warn / 360 kB error (now 293 kB), all scripts 440 / 500 kB,
per lazy route bundle (dashboard 21/25, transfer-form 15/18, pending 12/15, account-detail 12/15,
activity 4/6, not-found 2/4 kB), component styles 4 / 8 kB. LHCI thresholds are in `perf/thresholds.cjs`,
set 20-30% above the measured medians, so they hold today's numbers rather than the 0.95 target.

## Next steps

- **Live feed layout shift is the remaining problem** (`/activity` CLS 0.87 desktop, 1.9 mobile; `/` 0.36).
  Every new row pushes the rows below it down. Options: an "N new events" button while the user is not at
  the top, or absolutely positioned fixed-height rows moved with `transform`. Both change behaviour, so
  they need a design decision and test updates.
- Mobile TBT (300-470 ms) is script evaluation plus rendering 50 table rows or about 1,000 `<option>`s on
  the transfer form; try `@defer` for the below-the-fold accounts table and a searchable account picker.
- After either fix, tighten `perf/thresholds.cjs` toward score 0.95 and CLS 0.05.
