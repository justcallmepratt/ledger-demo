// Builds the JSON summary and the Markdown table from k6's end-of-test data (handleSummary).
// Only the steady phase is reported: warm-up and ramp requests carry phase:warmup.
import { ENDPOINTS, HOLD_S, RAMP_S, RATE_SCALE, SCENARIOS, WARMUP_S, BASE_RATES } from './config.js';

/** Parses a k6 metric key such as `http_req_duration{name:x,phase:steady}` into name and tags. */
function parseKey(key) {
  const open = key.indexOf('{');
  if (open === -1) {
    return { metric: key, tags: {} };
  }
  const tags = {};
  for (const part of key.slice(open + 1, key.lastIndexOf('}')).split(',')) {
    const colon = part.indexOf(':');
    tags[part.slice(0, colon).trim()] = part.slice(colon + 1).trim();
  }
  return { metric: key.slice(0, open), tags };
}

/** The values of the sub-metric `metric` whose tag set is exactly `tags`, or null. */
function find(data, metric, tags) {
  const wanted = Object.keys(tags);
  for (const key of Object.keys(data.metrics)) {
    const parsed = parseKey(key);
    if (parsed.metric !== metric) {
      continue;
    }
    const have = Object.keys(parsed.tags);
    if (have.length === wanted.length && wanted.every((t) => parsed.tags[t] === tags[t])) {
      const m = data.metrics[key];
      return m.values || m;
    }
  }
  return null;
}

function num(v, key) {
  return v && typeof v[key] === 'number' && !Number.isNaN(v[key]) ? v[key] : null;
}

function round(x, digits) {
  return x === null ? null : Math.round(x * 10 ** digits) / 10 ** digits;
}

function stats(data, scenario, endpoint) {
  const tags = { scenario, phase: 'steady' };
  if (endpoint) {
    tags.name = endpoint;
  }
  const dur = find(data, 'http_req_duration', tags);
  const reqs = find(data, 'http_reqs', tags);
  const err = find(data, 'ledger_errors', tags);
  const requests = num(reqs, 'count') || 0;
  const errorRate = num(err, 'rate');
  const out = {
    requests,
    reqPerSec: round(requests / HOLD_S, 1),
    errorRate: errorRate === null ? 0 : round(errorRate, 5),
    errors: err ? (num(err, 'passes') !== null ? num(err, 'passes') : Math.round((errorRate || 0) * requests)) : 0,
    latencyMs: {
      avg: round(num(dur, 'avg'), 2),
      p50: round(num(dur, 'med'), 2),
      p90: round(num(dur, 'p(90)'), 2),
      p95: round(num(dur, 'p(95)'), 2),
      p99: round(num(dur, 'p(99)'), 2),
      max: round(num(dur, 'max'), 2),
    },
  };
  if (!endpoint) {
    const created = find(data, 'ledger_transfers_created', tags);
    out.transfersPerSec = round((num(created, 'count') || 0) / HOLD_S, 1);
    const dropped = find(data, 'dropped_iterations', { scenario });
    out.droppedIterations = num(dropped, 'count') || 0;
    out.targetRate = Math.max(1, Math.round(BASE_RATES[scenario] * RATE_SCALE));
  }
  return out;
}

function cell(x) {
  return x === null || x === undefined ? 'n/a' : String(x);
}

function row(label, s) {
  const l = s.latencyMs;
  return `| ${label} | ${cell(s.reqPerSec)} | ${cell(l.p50)} | ${cell(l.p90)} | ${cell(l.p95)} | ${cell(l.p99)} | `
    + `${cell(l.max)} | ${round(s.errorRate * 100, 3)} |`;
}

export function buildReport(data) {
  const scenarios = {};
  for (const name of SCENARIOS) {
    const s = stats(data, name, null);
    s.endpoints = {};
    for (const ep of ENDPOINTS[name]) {
      s.endpoints[ep] = stats(data, name, ep);
    }
    scenarios[name] = s;
  }
  const checks = find(data, 'reconciliation_checks', {});
  const failures = find(data, 'reconciliation_failures', {});
  const reconciliation = {
    checks: num(checks, 'count') || 0,
    failures: num(failures, 'count') || 0,
  };
  reconciliation.ok = reconciliation.checks > 0 && reconciliation.failures === 0;

  let requests = 0;
  let errors = 0;
  for (const name of SCENARIOS) {
    requests += scenarios[name].requests;
    errors += scenarios[name].errors;
  }
  const json = {
    meta: {
      warmupSeconds: WARMUP_S, rampSeconds: RAMP_S, steadySeconds: HOLD_S, rateScale: RATE_SCALE,
      note: 'latency = k6 http_req_duration in ms, steady phase only; reqPerSec = steady requests / steady seconds',
    },
    totals: {
      requests,
      reqPerSec: round(requests / HOLD_S, 1),
      errors,
      errorRate: requests === 0 ? 0 : round(errors / requests, 5),
    },
    scenarios,
    reconciliation,
  };

  const lines = [];
  lines.push(`### k6 results (steady state ${HOLD_S}s, warm-up ${WARMUP_S}s + ramp ${RAMP_S}s excluded)`);
  lines.push('');
  lines.push('| Scenario | req/s | p50 ms | p90 ms | p95 ms | p99 ms | max ms | errors % |');
  lines.push('|---|---:|---:|---:|---:|---:|---:|---:|');
  for (const name of SCENARIOS) {
    lines.push(row(name, scenarios[name]));
  }
  lines.push(`| **all requests** | ${json.totals.reqPerSec} | | | | | | ${round(json.totals.errorRate * 100, 3)} |`);
  lines.push('');
  lines.push('| Scenario | target/s | transfers/s | dropped iterations |');
  lines.push('|---|---:|---:|---:|');
  for (const name of SCENARIOS) {
    const s = scenarios[name];
    lines.push(`| ${name} | ${s.targetRate} | ${s.transfersPerSec} | ${s.droppedIterations} |`);
  }
  lines.push('');
  lines.push('| Endpoint | req/s | p50 ms | p90 ms | p95 ms | p99 ms | max ms | errors % |');
  lines.push('|---|---:|---:|---:|---:|---:|---:|---:|');
  for (const name of SCENARIOS) {
    for (const ep of ENDPOINTS[name]) {
      lines.push(row(`${name} / ${ep}`, scenarios[name].endpoints[ep]));
    }
  }
  lines.push('');
  lines.push(`Reconciliation after the run: ${reconciliation.ok ? 'PASS (balanced, no account mismatches)' : 'FAIL'}`);
  lines.push('');
  return { json, markdown: lines.join('\n') };
}
