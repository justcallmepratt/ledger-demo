// Lighthouse CI assertions, derived from the baseline measured on the same build (median of 5
// runs, see PERFORMANCE.md) with 20-30% headroom for CI runner noise. Tighten after each fix.
// [url pattern, min performance score, max CLS, max LCP ms, max TBT ms]
const rows = {
  desktop: [
    ['/$', 0.75, 0.5, 1500, 200],
    ['/accounts/', 0.9, 0.2, 1500, 200],
    ['/activity$', 0.65, 1.2, 1500, 200],
    ['/transfers/new$', 0.95, 0.05, 1500, 200],
  ],
  mobile: [
    ['/$', 0.7, 0.2, 4500, 700],
    ['/accounts/', 0.75, 0.05, 4500, 600],
    ['/activity$', 0.5, 2.5, 4500, 600],
    ['/transfers/new$', 0.7, 0.05, 4500, 800],
  ],
};
const MAX_BYTES = 300_000; // total transfer size per page (baseline ~210 kB)

module.exports = Object.fromEntries(
  Object.entries(rows).map(([preset, list]) => [
    preset,
    list.map(([matchingUrlPattern, score, cls, lcp, tbt]) => ({
      matchingUrlPattern,
      assertions: {
        'categories:performance': ['error', { minScore: score, aggregationMethod: 'median-run' }],
        'cumulative-layout-shift': ['error', { maxNumericValue: cls, aggregationMethod: 'median-run' }],
        'largest-contentful-paint': ['error', { maxNumericValue: lcp, aggregationMethod: 'median-run' }],
        'total-blocking-time': ['error', { maxNumericValue: tbt, aggregationMethod: 'median-run' }],
        'total-byte-weight': ['error', { maxNumericValue: MAX_BYTES, aggregationMethod: 'median-run' }],
      },
    })),
  ]),
);
