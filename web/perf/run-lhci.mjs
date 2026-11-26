#!/usr/bin/env node
// Runs Lighthouse CI for both presets against the production build and prints the summary.
//   node perf/run-lhci.mjs [--presets desktop,mobile] [--no-assert]
// Exit code is non-zero if an assertion fails (unless --no-assert).
import { spawnSync } from 'node:child_process';
import { rmSync } from 'node:fs';

const args = process.argv.slice(2);
const presets = (args.includes('--presets') ? args[args.indexOf('--presets') + 1] : 'desktop,mobile').split(',');
const assert = !args.includes('--no-assert');
const run = (cmd, a, env = {}) => spawnSync(cmd, a, { stdio: 'inherit', env: { ...process.env, ...env } }).status ?? 1;
const lhci = (sub, env) => run('pnpm', ['exec', 'lhci', sub, '--config=perf/lighthouserc.cjs'], env);

rmSync('.lighthouseci', { recursive: true, force: true });
let failed = false;
for (const preset of presets) {
  const env = { LH_PRESET: preset, LH_ASSERT: assert ? '1' : '0' };
  console.log(`\n=== Lighthouse CI: ${preset} ===`);
  if (lhci('collect', env) !== 0) process.exit(1);
  if (lhci('upload', env) !== 0) process.exit(1); // filesystem: keeps lhr-*.json and html per preset
  if (assert && lhci('assert', env) !== 0) failed = true;
}
run('node', ['perf/summarize-lhci.mjs', ...presets]);
process.exit(failed ? 1 : 0);
