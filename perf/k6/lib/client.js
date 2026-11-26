import http from 'k6/http';
import { check } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { API, WARMUP_S, RAMP_S } from './config.js';

// 1 = unexpected status or body, 0 = as expected. Reported per scenario, endpoint and phase.
export const errors = new Rate('ledger_errors');
// Transfers the ledger accepted (a batch counts every leg), for throughput in transfers/s.
export const transfersCreated = new Counter('ledger_transfers_created');

const HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };
let logged = 0;

/** 'warmup' for the first WARMUP_S + RAMP_S seconds of the scenario, then 'steady'. */
export function phase() {
  const elapsedMs = Date.now() - exec.scenario.startTime;
  return elapsedMs < (WARMUP_S + RAMP_S) * 1000 ? 'warmup' : 'steady';
}

/**
 * One checked request. `ep` is the static endpoint tag, `expected` the accepted status codes and
 * `validate` an optional body check, `legs` how many transfers a successful call adds to the ledger.
 * Anything else counts as an error in ledger_errors.
 */
export function call(method, path, body, ep, expected, validate, legs) {
  const tags = { name: ep, phase: phase() };
  const res = http.request(method, API + path, body === null ? null : JSON.stringify(body), {
    headers: HEADERS,
    tags,
    timeout: '30s',
  });
  let ok = expected.indexOf(res.status) !== -1;
  if (ok && validate) {
    try {
      ok = validate(res) === true;
    } catch (e) {
      ok = false;
    }
  }
  check(res, { [`${ep} ok`]: () => ok }, tags);
  errors.add(!ok, tags);
  if (ok && legs) {
    transfersCreated.add(legs, tags);
  }
  if (!ok && logged < 5) {
    logged += 1;
    console.error(`${ep}: unexpected ${res.status} (wanted ${expected.join('/')}): ${String(res.body).slice(0, 300)}`);
  }
  return { res, ok };
}

/** Plain request for setup and teardown: throws on an unexpected status. */
export function must(method, path, body, expected) {
  const res = http.request(method, API + path, body === null ? null : JSON.stringify(body), {
    headers: HEADERS,
    timeout: '60s',
  });
  if (expected.indexOf(res.status) === -1) {
    throw new Error(`${method} ${path} -> ${res.status}: ${String(res.body).slice(0, 300)}`);
  }
  return res;
}
