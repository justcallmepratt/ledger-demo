// Lighthouse CI config for the production build, served by perf/mock-server.mjs (same origin,
// deterministic API data, SSE stream at ~20 events/s).
//   LH_PRESET=desktop|mobile  (default desktop)
//   LH_ASSERT=0               skip assertions (used to take a baseline)
const preset = process.env.LH_PRESET === 'mobile' ? 'mobile' : 'desktop';
const PORT = process.env.PERF_PORT || '4300';
const ACCOUNT_ID = '62a40cf2-52d1-4221-a74f-a8423191ebf1'; // first mock account, stable across runs
const thresholds = require('./thresholds.cjs')[preset];


module.exports = {
  ci: {
    collect: {
      numberOfRuns: 5,
      startServerCommand: `node perf/mock-server.mjs --port ${PORT}`,
      startServerReadyPattern: 'mock-server: http',
      startServerReadyTimeout: 30000,
      url: ['/', `/accounts/${ACCOUNT_ID}`, '/transfers/new', '/activity'].map(
        (p) => `http://127.0.0.1:${PORT}${p}`,
      ),
      settings: {
        ...(preset === 'desktop' ? { preset: 'desktop' } : {}),
        onlyCategories: ['performance'],
        chromeFlags: '--no-sandbox --headless=new --disable-dev-shm-usage',
      },
    },
    upload: { target: 'filesystem', outputDir: `.lighthouseci/reports-${preset}` },
    assert: process.env.LH_ASSERT === '0' ? {} : { assertMatrix: thresholds },
  },
};
