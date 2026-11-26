#!/usr/bin/env node
// Median and worst run per URL and preset from the LHCI filesystem reports.
//   node perf/summarize-lhci.mjs [desktop mobile] [--md]
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const argv = process.argv.slice(2);
const md = argv.includes('--md');
const presets = argv.filter((a) => !a.startsWith('--'));
if (presets.length === 0) presets.push('desktop', 'mobile');

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
// "worst" = the run with the lowest score for score, the highest value for everything else.
const METRICS = [
  ['score', (l) => l.categories.performance.score * 100, 0, true],
  ['LCP ms', (l) => l.audits['largest-contentful-paint'].numericValue, 0, false],
  ['TBT ms', (l) => l.audits['total-blocking-time'].numericValue, 0, false],
  ['CLS', (l) => l.audits['cumulative-layout-shift'].numericValue, 3, false],
  ['FCP ms', (l) => l.audits['first-contentful-paint'].numericValue, 0, false],
  ['transfer kB', (l) => l.audits['total-byte-weight'].numericValue / 1000, 1, false],
];
const fmt = (v, d) => (d === 0 ? Math.round(v) : v.toFixed(d));
const rows = [];
for (const preset of presets) {
  const dir = join('.lighthouseci', `reports-${preset}`);
  if (!existsSync(dir)) continue;
  const lhrs = readdirSync(dir).filter((f) => /\.report\.json$/.test(f)).map((f) => JSON.parse(readFileSync(join(dir, f), 'utf8')));
  const byUrl = new Map();
  for (const l of lhrs) {
    const path = new URL(l.finalDisplayedUrl ?? l.finalUrl).pathname.replace(/^\/accounts\/[^/]+$/, '/accounts/:id');
    byUrl.set(path, [...(byUrl.get(path) ?? []), l]);
  }
  for (const [path, runs] of [...byUrl].sort()) {
    const cells = METRICS.map(([, get, d, lowIsBad]) => {
      const vals = runs.map(get);
      return `${fmt(median(vals), d)} / ${fmt(lowIsBad ? Math.min(...vals) : Math.max(...vals), d)}`;
    });
    rows.push([preset, path, String(runs.length), ...cells]);
  }
}
const head = ['preset', 'url', 'runs', ...METRICS.map((m) => m[0])];
if (md) {
  console.log(`| ${head.join(' | ')} |\n|${head.map(() => '---').join('|')}|`);
  for (const r of rows) console.log(`| ${r.join(' | ')} |`);
} else {
  console.log('\ncells are median / worst run');
  const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => r[i].length)));
  const line = (r) => r.map((c, i) => c.padEnd(w[i])).join('  ');
  console.log(line(head));
  for (const r of rows) console.log(line(r));
}
