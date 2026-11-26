// ledger-core load test. Run with:  k6 run perf/k6/ledger.js   (see docs/PERFORMANCE.md)
//
// Seven pieces share one timeline and run concurrently, like mixed production traffic:
//   setup               ~200 accounts (some limit-guarded and funded by a settlement account)
//   single_transfers    random-pair POST /transfers
//   hot_account         every transfer credits one fee account (lock contention)
//   linked_batches      POST /transfers/batch with 5-10 linked legs (one atomic chain)
//   two_phase           pending -> post / partial post / void (a few left to expire)
//   reads               GET account, GET account transfers, GET /transfers?pendingStatus=PENDING
//   idempotent_retries  resubmitting ids created in setup (replays and conflicts)
//   teardown            GET /reconciliation: the run FAILS unless the books balance
//
// Results are tagged per scenario, endpoint (`name`) and phase (warm-up vs steady); only the
// steady phase is reported.
import { Counter, Trend } from 'k6/metrics';
import http from 'k6/http';
import {
  API, BASE_RATES, ENDPOINTS, FUNDING, HOLD_S, LEDGER, POOL_FREE, POOL_LIMITED, RAMP_S, RATE_SCALE,
  REPLAY_POOL, SCENARIOS, WARMUP_S,
} from './lib/config.js';
import { amount, newRunId, pick, pickDistinct, pickPair, rnd, setupUuid, uuid } from './lib/ids.js';
import { call, must, transfersCreated } from './lib/client.js';
import { buildReport } from './lib/report.js';

const reconciliationFailures = new Counter('reconciliation_failures');
const reconciliationChecks = new Counter('reconciliation_checks');
const reconciliationMs = new Trend('reconciliation_ms', true);

// ---------------------------------------------------------------------------- options

function arrivalRate(name) {
  const target = Math.max(1, Math.round(BASE_RATES[name] * RATE_SCALE));
  return {
    executor: 'ramping-arrival-rate',
    exec: name,
    startRate: 1,
    timeUnit: '1s',
    preAllocatedVUs: Math.max(20, Math.round(target / 2)),
    maxVUs: Math.max(100, target * 4),
    gracefulStop: '30s',
    stages: [
      { target: Math.max(1, Math.round(target / 2)), duration: `${WARMUP_S}s` }, // warm-up
      { target, duration: `${RAMP_S}s` }, // ramp
      { target, duration: `${HOLD_S}s` }, // steady state
    ],
  };
}

const scenarios = {};
const thresholds = {
  // The books must balance after the run.
  reconciliation_failures: ['count==0'],
};
for (const name of SCENARIOS) {
  scenarios[name] = arrivalRate(name);
  const steady = `scenario:${name},phase:steady`;
  // Thresholds only exist to make k6 keep a sub-metric per scenario / endpoint for the report.
  // The one real gate is the error rate: a run with more than 1 % errors measures a broken system.
  thresholds[`http_req_duration{${steady}}`] = ['p(95)>=0'];
  thresholds[`http_reqs{${steady}}`] = ['count>=0'];
  thresholds[`ledger_errors{${steady}}`] = ['rate<0.01'];
  thresholds[`ledger_transfers_created{${steady}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
  for (const ep of ENDPOINTS[name]) {
    thresholds[`http_req_duration{${steady},name:${ep}}`] = ['p(95)>=0'];
    thresholds[`http_reqs{${steady},name:${ep}}`] = ['count>=0'];
    thresholds[`ledger_errors{${steady},name:${ep}}`] = ['rate<0.01'];
  }
}

export const options = {
  scenarios,
  thresholds,
  setupTimeout: '180s',
  teardownTimeout: '180s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  userAgent: 'ledger-core-k6/1.0',
};

// ---------------------------------------------------------------------------- setup

function createAccount(id, code, name, flags) {
  must('POST', '/accounts', { id, ledger: LEDGER, code, name, flags }, [201]);
}

function submitBatch(transfers) {
  const res = must('POST', '/transfers/batch', { transfers }, [200]);
  const results = res.json('results');
  for (const r of results) {
    if (r.code !== 'created') {
      throw new Error(`setup transfer ${r.id} was not created: ${r.code} ${r.detail || ''}`);
    }
  }
}

export function setup() {
  const run = newRunId();
  const settlement = setupUuid(run, 1, 0);
  const fee = setupUuid(run, 1, 1);
  createAccount(settlement, 1, 'settlement', {});
  createAccount(fee, 2, 'fee (hot account)', {});

  const free = [];
  for (let i = 0; i < POOL_FREE; i++) {
    const id = setupUuid(run, 2, i);
    createAccount(id, 10, `wallet-${i}`, { history: i % 8 === 0 });
    free.push(id);
  }
  const limited = [];
  for (let i = 0; i < POOL_LIMITED; i++) {
    const id = setupUuid(run, 3, i);
    createAccount(id, 20, `limited-${i}`, { debitsMustNotExceedCredits: true, history: i % 2 === 0 });
    limited.push(id);
  }

  // Fund every limit-guarded account from the settlement account (one atomic call, unlinked legs).
  submitBatch(limited.map((id, i) => ({
    id: setupUuid(run, 4, i), debitAccountId: settlement, creditAccountId: id,
    amount: FUNDING, ledger: LEDGER, code: 1, reference: 'funding',
  })));

  const accounts = free.concat(limited);

  // Transfers that idempotent_retries will resubmit.
  const replay = [];
  for (let i = 0; i < REPLAY_POOL; i++) {
    const [debit, credit] = pickPair(accounts);
    replay.push({
      id: setupUuid(run, 5, i), debitAccountId: debit, creditAccountId: credit,
      amount: amount(1, 1000), ledger: LEDGER, code: 1, reference: `replay-${i}`,
    });
  }
  for (let i = 0; i < replay.length; i += 100) {
    submitBatch(replay.slice(i, i + 100));
  }
  return { run, settlement, fee, accounts, replay };
}

// ---------------------------------------------------------------------------- scenarios

/** Random pair of accounts, one transfer, expect 201. */
export function single_transfers(data) {
  const [debit, credit] = pickPair(data.accounts);
  call('POST', '/transfers', {
    id: uuid(data.run, 'single_transfers', 0), debitAccountId: debit, creditAccountId: credit,
    amount: amount(1, 10000), ledger: LEDGER, code: 1, reference: `pay-${rnd(1000000)}`,
  }, 'transfer_create', [201], undefined, 1);
}

/** Every transfer credits the same fee account: serialised on its row lock. */
export function hot_account(data) {
  const debit = pick(data.accounts);
  call('POST', '/transfers', {
    id: uuid(data.run, 'hot_account', 0), debitAccountId: debit, creditAccountId: data.fee,
    amount: amount(1, 100), ledger: LEDGER, code: 3, reference: 'fee',
  }, 'transfer_hot', [201], undefined, 1);
}

/** One batch = one atomic chain of 5-10 linked legs a->b->c->... Every leg must be 'created'. */
export function linked_batches(data) {
  const legs = 5 + rnd(6);
  const chain = pickDistinct(data.accounts, legs + 1);
  const transfers = [];
  for (let i = 0; i < legs; i++) {
    transfers.push({
      id: uuid(data.run, 'linked_batches', i), debitAccountId: chain[i], creditAccountId: chain[i + 1],
      amount: amount(1, 5000), ledger: LEDGER, code: 4,
      flags: { linked: i < legs - 1 },
    });
  }
  call('POST', '/transfers/batch', { transfers }, 'batch_linked', [200], (res) => {
    const results = res.json('results');
    return results.length === legs && results.every((r) => r.code === 'created');
  }, legs);
}

/** pending -> (post | partial post | void); about one in ten is left to expire after 20 s. */
export function two_phase(data) {
  const [debit, credit] = pickPair(data.accounts);
  const pendingId = uuid(data.run, 'two_phase', 0);
  const pendingAmount = 100 + rnd(9900);
  const leaveOpen = Math.random() < 0.1;
  const created = call('POST', '/transfers', {
    id: pendingId, debitAccountId: debit, creditAccountId: credit, amount: String(pendingAmount),
    ledger: LEDGER, code: 5, flags: { pending: true }, timeoutSeconds: leaveOpen ? 20 : 3600,
  }, 'tp_pending', [201], undefined, 1);
  if (!created.ok || leaveOpen) {
    return;
  }
  const resolveId = uuid(data.run, 'two_phase', 1);
  const roll = Math.random();
  if (roll < 0.6) {
    call('POST', '/transfers', {
      id: resolveId, pendingId, amount: '0', flags: { postPendingTransfer: true },
    }, 'tp_post', [201], undefined, 1);
  } else if (roll < 0.8) {
    call('POST', '/transfers', {
      id: resolveId, pendingId, amount: String(1 + rnd(pendingAmount - 1)), flags: { postPendingTransfer: true },
    }, 'tp_post_partial', [201], undefined, 1);
  } else {
    call('POST', '/transfers', {
      id: resolveId, pendingId, amount: '0', flags: { voidPendingTransfer: true },
    }, 'tp_void', [201], undefined, 1);
  }
}

/** Resubmits transfers created in setup: 90 % identical (expect 200), 10 % altered (expect 409). */
export function idempotent_retries(data) {
  const original = pick(data.replay);
  if (Math.random() < 0.9) {
    call('POST', '/transfers', original, 'transfer_replay', [200], (res) => res.json('id') === original.id);
  } else {
    const changed = Object.assign({}, original, { amount: String(Number(original.amount) + 1) });
    call('POST', '/transfers', changed, 'transfer_conflict', [409],
      (res) => res.json('code') === 'exists_with_different_fields');
  }
}

/** 50 % GET account, 30 % an account's transfers page, 20 % the pending-transfers list. */
export function reads(data) {
  const roll = Math.random();
  if (roll < 0.5) {
    const id = pick(data.accounts);
    call('GET', `/accounts/${id}`, null, 'acct_get', [200], (res) => res.json('id') === id);
  } else if (roll < 0.8) {
    const id = pick(data.accounts);
    call('GET', `/accounts/${id}/transfers?limit=20`, null, 'acct_transfers', [200],
      (res) => Array.isArray(res.json('items')));
  } else {
    call('GET', '/transfers?pendingStatus=PENDING&limit=50', null, 'transfers_pending', [200],
      (res) => Array.isArray(res.json('items')));
  }
}

// ---------------------------------------------------------------------------- teardown

/** The books must balance and no stored counter may differ from the recomputed one. */
export function teardown() {
  reconciliationChecks.add(1);
  const res = http.get(`${API}/reconciliation`, { timeout: '120s', tags: { name: 'reconciliation' } });
  reconciliationMs.add(res.timings.duration);
  let ok = false;
  let detail = `status ${res.status}`;
  if (res.status === 200) {
    const report = res.json();
    const mismatches = report.accountMismatches.length;
    const unbalanced = report.ledgers.filter((l) => !l.balanced).length;
    ok = report.balanced === true && mismatches === 0 && unbalanced === 0;
    detail = `balanced=${report.balanced} accountMismatches=${mismatches} unbalancedLedgers=${unbalanced}`;
  }
  console.log(`reconciliation: ${detail}`);
  if (!ok) {
    reconciliationFailures.add(1);
    console.error(`RECONCILIATION FAILED: ${detail} ${String(res.body).slice(0, 500)}`);
  }
}

// ---------------------------------------------------------------------------- summary

export function handleSummary(data) {
  const report = buildReport(data);
  const dir = __ENV.OUT_DIR || 'perf-out';
  return {
    [`${dir}/summary.json`]: JSON.stringify(report.json, null, 2),
    [`${dir}/summary.md`]: report.markdown,
    stdout: `\n${report.markdown}\n`,
  };
}
