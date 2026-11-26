// Shared configuration for the ledger-core k6 load test. Everything can be overridden
// with -e NAME=value (or the workflow_dispatch inputs in .github/workflows/perf.yml).

function num(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (Number.isNaN(value)) {
    throw new Error(`${name} must be a number, got "${raw}"`);
  }
  return value;
}

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const API = `${BASE_URL}/api/v1`;

// Timeline per scenario (all scenarios share it, so the phases line up):
//   warm-up  0 -> 50 % of target   (JIT, pools, caches; excluded from the results)
//   ramp     50 % -> 100 %         (excluded from the results)
//   steady   hold at target        (the measured window, >= 180 s)
export const WARMUP_S = num('WARMUP_S', 30);
export const RAMP_S = num('RAMP_S', 30);
export const HOLD_S = num('HOLD_S', 200);
export const RATE_SCALE = num('RATE_SCALE', 1);

export const LEDGER = 840;
export const POOL_FREE = num('POOL_FREE', 160); // accounts without balance limits
export const POOL_LIMITED = num('POOL_LIMITED', 40); // debitsMustNotExceedCredits, funded by the settlement account
export const FUNDING = '1000000000000'; // minor units per limited account, far above what a run spends
export const REPLAY_POOL = num('REPLAY_POOL', 200); // transfers created in setup and resubmitted by idempotent_retries

// Target arrival rate per scenario, in iterations per second at scale 1.
// linked_batches counts batches (5-10 legs each), two_phase counts pending->resolve flows (2 requests).
export const BASE_RATES = {
  single_transfers: num('RATE_SINGLE', 150),
  hot_account: num('RATE_HOT', 60),
  linked_batches: num('RATE_BATCH', 15),
  two_phase: num('RATE_TWO_PHASE', 40),
  reads: num('RATE_READS', 120),
  idempotent_retries: num('RATE_IDEMPOTENT', 40),
};

// Endpoint tags (the `name` tag) used by each scenario, so results can be split per endpoint.
export const ENDPOINTS = {
  single_transfers: ['transfer_create'],
  hot_account: ['transfer_hot'],
  linked_batches: ['batch_linked'],
  two_phase: ['tp_pending', 'tp_post', 'tp_post_partial', 'tp_void'],
  reads: ['acct_get', 'acct_transfers', 'transfers_pending'],
  idempotent_retries: ['transfer_replay', 'transfer_conflict'],
};

// Stable scenario numbers, used to build collision-free ids.
export const SCENARIO_IDS = {
  setup: 0,
  single_transfers: 1,
  hot_account: 2,
  linked_batches: 3,
  two_phase: 4,
  idempotent_retries: 5,
  reads: 6,
};

export const SCENARIOS = Object.keys(BASE_RATES);
