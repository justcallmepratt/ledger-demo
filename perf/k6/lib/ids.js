import exec from 'k6/execution';
import { SCENARIO_IDS } from './config.js';

function hex(n, width) {
  return n.toString(16).padStart(width, '0').slice(-width);
}

/** Random 8-hex-digit run prefix: ids from different runs never collide, even on a reused database. */
export function newRunId() {
  return hex(Math.floor(Math.random() * 0xffffffff), 8);
}

/**
 * A valid UUID that is unique by construction: run prefix, scenario number, a sub-id
 * (for several ids per iteration) and the scenario-wide iteration counter.
 */
export function uuid(run, scenario, sub) {
  const n = exec.scenario.iterationInTest;
  return `${run}-${hex(SCENARIO_IDS[scenario], 4)}-4${hex(sub, 3)}-a000-${hex(n, 12)}`;
}

/** Ids for setup-time objects (accounts, funding and replay transfers). */
export function setupUuid(run, kind, n) {
  return `${run}-0000-4${hex(kind, 3)}-a000-${hex(n, 12)}`;
}

export function rnd(n) {
  return Math.floor(Math.random() * n);
}

export function pick(list) {
  return list[rnd(list.length)];
}

/** Two different elements of the list. */
export function pickPair(list) {
  const a = rnd(list.length);
  let b = rnd(list.length - 1);
  if (b >= a) {
    b += 1;
  }
  return [list[a], list[b]];
}

/** `count` distinct elements of the list (partial Fisher-Yates on a copy of the indices). */
export function pickDistinct(list, count) {
  const idx = list.map((_, i) => i);
  const out = [];
  for (let i = 0; i < count; i++) {
    const j = i + rnd(idx.length - i);
    const t = idx[i];
    idx[i] = idx[j];
    idx[j] = t;
    out.push(list[idx[i]]);
  }
  return out;
}

export function amount(min, max) {
  return String(min + rnd(max - min + 1));
}
