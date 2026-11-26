#!/usr/bin/env node
// Deterministic mock of the ledger-core API (docs/API.md) plus a static host for the production
// build, for performance runs only. Node built-ins only; no dependencies.
//
//   node perf/mock-server.mjs [--port 4300] [--dist dist/web/browser]
//
// Realistic volumes, all derived from a seeded PRNG so every run sees identical data:
//   500 accounts (keyset-paged), 2,000 activity items, 200 transfers per account,
//   an SSE stream emitting ~20 events/s (override per connection with ?rate=N&count=M).
// Static files are served the way a production host would: brotli/gzip, immutable cache headers
// for hashed assets, SPA fallback to index.html.
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { brotliCompressSync, gzipSync, constants as zc } from 'node:zlib';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const PORT = Number(opt('port', process.env.PORT ?? 4300));
const HERE = fileURLToPath(new URL('.', import.meta.url));
const DIST = resolve(opt('dist', join(HERE, '..', 'dist', 'web', 'browser')));
const API = '/api/v1';
const DEFAULT_RATE = 20; // SSE events per second

// ---- deterministic data -------------------------------------------------------------------
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
/** A stable UUID-shaped id from a namespace and an index. */
function uuid(ns, n) {
  const h = (x) => (Math.imul(x ^ 0x9e3779b9, 0x85ebca6b) >>> 0).toString(16).padStart(8, '0');
  const a = h(ns * 7919 + n);
  const b = h(ns * 104729 + n * 31 + 1);
  const c = h(ns * 1299709 + n * 17 + 2);
  const d = h(ns * 15485863 + n * 13 + 3);
  return `${a}-${b.slice(0, 4)}-4${b.slice(5, 8)}-a${c.slice(1, 4)}-${c.slice(4)}${d}`;
}

const BASE = Date.parse('2026-11-26T08:00:00Z');
const iso = (ms) => new Date(ms).toISOString();
const LEDGERS = [
  { id: 840, currency: 'USD', assetScale: 2 },
  { id: 978, currency: 'EUR', assetScale: 2 },
  { id: 392, currency: 'JPY', assetScale: 0 },
];
const FIRST_NAMES = ['Alice', 'Bob', 'Carol', 'Dave', 'Erin', 'Frank', 'Grace', 'Heidi', 'Ivan', 'Judy'];
const KINDS = ['wallet', 'savings', 'settlement', 'escrow', 'fees', 'payroll'];

const rngA = mulberry32(1);
const ACCOUNT_COUNT = 500;
const accounts = Array.from({ length: ACCOUNT_COUNT }, (_, i) => {
  const r = rngA;
  const history = i % 4 === 0;
  const dp = Math.floor(r() * 5_000_000);
  const cp = Math.floor(r() * 5_000_000);
  return {
    id: uuid(1, i),
    ledger: LEDGERS[i % 3].id,
    code: 1 + (i % 9),
    name: `${FIRST_NAMES[i % 10]} ${KINDS[i % 6]} ${String(i).padStart(3, '0')}`,
    flags: {
      debitsMustNotExceedCredits: i % 3 === 0,
      creditsMustNotExceedDebits: i % 7 === 0,
      history,
      closed: i % 50 === 49,
    },
    debitsPending: String(Math.floor(r() * 50_000)),
    debitsPosted: String(dp),
    creditsPending: String(Math.floor(r() * 50_000)),
    creditsPosted: String(cp),
    createdAt: iso(BASE - (ACCOUNT_COUNT - i) * 60_000),
  };
});
const accountById = new Map(accounts.map((a) => [a.id, a]));

const rngT = mulberry32(2);
function transfersFor(account, count = 200) {
  const idx = accounts.indexOf(account);
  return Array.from({ length: count }, (_, k) => {
    const out = rngT() < 0.5;
    const other = accounts[(idx + 1 + Math.floor(rngT() * 40)) % ACCOUNT_COUNT];
    const pending = k % 9 === 0;
    return {
      id: uuid(3, idx * 1000 + k),
      debitAccountId: out ? account.id : other.id,
      creditAccountId: out ? other.id : account.id,
      amount: String(100 + Math.floor(rngT() * 900_000)),
      ledger: account.ledger,
      code: 1 + (k % 5),
      flags: { linked: false, pending, postPendingTransfer: false, voidPendingTransfer: false },
      pendingId: null,
      timeoutSeconds: pending ? 3600 : 0,
      reference: k % 4 === 0 ? `INV-${100000 + k}` : null,
      seq: String(1_000_000 - idx * 100 - k),
      timestamp: iso(BASE - k * 45_000),
      pendingStatus: pending ? 'PENDING' : null,
      expiresAt: pending ? iso(BASE + 3_600_000) : null,
    };
  });
}
const transferCache = new Map();
const transfersOf = (a) => {
  if (!transferCache.has(a.id)) transferCache.set(a.id, transfersFor(a));
  return transferCache.get(a.id);
};

function balancesFor(account) {
  return transfersOf(account).slice(0, 100).map((t, k) => ({
    transferId: t.id,
    seq: t.seq,
    timestamp: t.timestamp,
    debitsPending: '0',
    debitsPosted: String(10_000 + k * 137),
    creditsPending: '0',
    creditsPosted: String(20_000 + k * 251),
  }));
}

const EVENT_TYPES = ['transfer.created', 'transfer.posted', 'transfer.voided', 'transfer.expired', 'account.created'];
/** `n` is the global event index: 0..1999 are history (older), stream events continue after it. */
function activityItem(n, ns, tOffset) {
  const type = EVENT_TYPES[n % 5];
  const a = accounts[(n * 7) % ACCOUNT_COUNT];
  const isAccount = type === 'account.created';
  return {
    eventId: uuid(ns, n),
    type,
    seq: String(2_000_000 + (ns === 4 ? n : 5_000 + n)),
    transferId: isAccount ? null : uuid(3, (n % 400) * 1000 + (n % 200)),
    accountId: a.id,
    amount: isAccount ? null : String(100 + ((n * 7919) % 900_000)),
    ledger: a.ledger,
    occurredAt: iso(BASE + tOffset),
    // The #e<digits> tag lets the interaction harness find the row for each event.
    summary: `${type.replace('.', ' ')} ${a.name} #e${String(ns === 4 ? 1_000_000 + n : n).padStart(7, '0')}`,
  };
}
const HISTORY = Array.from({ length: 2000 }, (_, i) => activityItem(i, 5, -(2000 - i) * 30_000)).reverse();

// ---- helpers ------------------------------------------------------------------------------
const encCursor = (i) => Buffer.from(`k:${i}`).toString('base64url');
const decCursor = (c) => {
  if (!c) return 0;
  const m = /^k:(\d+)$/.exec(Buffer.from(c, 'base64url').toString());
  return m ? Number(m[1]) : 0;
};
function page(list, url) {
  const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
  const start = decCursor(url.searchParams.get('cursor'));
  const items = list.slice(start, start + limit);
  const next = start + limit < list.length ? encCursor(start + limit) : null;
  return { items, nextCursor: next };
}
const gzCache = new Map();
function json(res, status, body, req) {
  const data = Buffer.from(JSON.stringify(body));
  const headers = {
    'content-type': status >= 400 ? 'application/problem+json' : 'application/json',
    'cache-control': 'no-store',
    vary: 'Accept-Encoding',
  };
  let out = data;
  // A real deployment compresses JSON at the edge; do the same so transfer sizes are meaningful.
  if (req && data.length > 1024 && /\bgzip\b/.test(String(req.headers['accept-encoding'] ?? ''))) {
    const key = `${req.url}`;
    if (!gzCache.has(key)) gzCache.set(key, gzipSync(data, { level: 6 }));
    out = gzCache.get(key);
    headers['content-encoding'] = 'gzip';
  }
  headers['content-length'] = out.length;
  res.writeHead(status, headers);
  res.end(out);
}
const problem = (res, status, code, title) => json(res, status, { type: 'about:blank', title, status, code });

// ---- SSE ----------------------------------------------------------------------------------
function stream(req, res, url) {
  const rate = Math.max(1, Number(url.searchParams.get('rate') ?? DEFAULT_RATE));
  const count = Number(url.searchParams.get('count') ?? Infinity);
  const lastId = Number(req.headers['last-event-id'] ?? NaN);
  res.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-store',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  res.write(': connected\n\n');
  // Event i occurs at BASE + 1000 + i*50ms of logical time; ids and content depend only on i.
  let i = Number.isFinite(lastId) ? lastId + 1 : 0;
  let sent = 0;
  const batchMs = 10; // emit in 10 ms ticks, as many events as the rate asks for
  const perTick = rate * (batchMs / 1000);
  let carry = 0;
  const timer = setInterval(() => {
    carry += perTick;
    let out = '';
    while (carry >= 1 && sent < count) {
      carry -= 1;
      const item = activityItem(i, 4, 1000 + i * 50);
      out += `event: activity\nid: ${i}\ndata: ${JSON.stringify(item)}\n\n`;
      i++;
      sent++;
    }
    if (out) res.write(out);
    if (sent >= count) clearInterval(timer);
  }, batchMs);
  const beat = setInterval(() => res.write(': heartbeat\n\n'), 15_000);
  req.on('close', () => {
    clearInterval(timer);
    clearInterval(beat);
  });
}

// ---- API router ---------------------------------------------------------------------------
function api(req, res, url) {
  const path = url.pathname.slice(API.length) || '/';
  if (req.method !== 'GET') return problem(res, 405, 'method_not_allowed', 'Mock server is read-only');
  if (path === '/ledgers') return json(res, 200, LEDGERS, req);
  if (path === '/accounts') return json(res, 200, page(accounts, url), req);
  if (path === '/reconciliation') {
    const sum = (k) => accounts.reduce((s, a) => s + BigInt(a[k]), 0n).toString();
    return json(res, 200, {
      checkedAt: iso(BASE),
      balanced: true,
      ledgers: LEDGERS.map((l) => ({
        ledger: l.id,
        debitsPosted: sum('debitsPosted'),
        creditsPosted: sum('debitsPosted'),
        debitsPending: sum('debitsPending'),
        creditsPending: sum('debitsPending'),
        balanced: true,
      })),
      accountMismatches: [],
    }, req);
  }
  if (path === '/activity') {
    const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
    return json(res, 200, HISTORY.slice(0, limit), req);
  }
  if (path === '/activity/stream') return stream(req, res, url);
  const m = /^\/accounts\/([^/]+)(?:\/(transfers|balances))?$/.exec(path);
  if (m) {
    const account = accountById.get(decodeURIComponent(m[1]));
    if (!account) return problem(res, 404, 'account_not_found', 'Account not found');
    if (!m[2]) return json(res, 200, account, req);
    if (m[2] === 'transfers') return json(res, 200, page(transfersOf(account), url), req);
    if (!account.flags.history) return problem(res, 422, 'history_not_enabled', 'History is not enabled');
    return json(res, 200, page(balancesFor(account), url), req);
  }
  return problem(res, 404, 'not_found', 'Not found');
}

// ---- static host --------------------------------------------------------------------------
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
};
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.svg', '.json', '.txt', '.ico']);
const fileCache = new Map();
function load(file) {
  const st = statSync(file);
  const hit = fileCache.get(file);
  if (hit && hit.mtime === st.mtimeMs) return hit;
  const raw = readFileSync(file);
  const ext = extname(file);
  const entry = { mtime: st.mtimeMs, raw, type: TYPES[ext] ?? 'application/octet-stream' };
  if (COMPRESSIBLE.has(ext)) {
    entry.br = brotliCompressSync(raw, { params: { [zc.BROTLI_PARAM_QUALITY]: 11 } });
    entry.gz = gzipSync(raw, { level: 9 });
  }
  fileCache.set(file, entry);
  return entry;
}
function serveStatic(req, res, url) {
  let rel = normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
  if (rel.startsWith('..')) return json(res, 400, { error: 'bad path' });
  let file = join(DIST, rel);
  const isAsset = existsSync(file) && statSync(file).isFile();
  if (!isAsset) {
    if (extname(rel)) return json(res, 404, { error: 'not found' });
    file = join(DIST, 'index.html'); // SPA fallback
  }
  const entry = load(file);
  const hashed = /[-.][A-Za-z0-9_]{8}\.(js|css|woff2?)$/.test(file) && file.includes(DIST);
  const accept = String(req.headers['accept-encoding'] ?? '');
  const headers = {
    'content-type': entry.type,
    'cache-control': hashed ? 'public, max-age=31536000, immutable' : 'no-cache',
    vary: 'Accept-Encoding',
  };
  let body = entry.raw;
  if (entry.br && /\bbr\b/.test(accept)) {
    body = entry.br;
    headers['content-encoding'] = 'br';
  } else if (entry.gz && /\bgzip\b/.test(accept)) {
    body = entry.gz;
    headers['content-encoding'] = 'gzip';
  }
  headers['content-length'] = body.length;
  res.writeHead(200, headers);
  res.end(req.method === 'HEAD' ? undefined : body);
}

if (!existsSync(join(DIST, 'index.html'))) {
  console.error(`mock-server: ${DIST}/index.html not found; run "pnpm build" first`);
  process.exit(1);
}
const server = createServer((req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (url.pathname.startsWith(`${API}/`)) return api(req, res, url);
    return serveStatic(req, res, url);
  } catch (e) {
    res.writeHead(500).end(String(e));
  }
});
server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock-server: http://127.0.0.1:${PORT}  (dist ${DIST}, ${ACCOUNT_COUNT} accounts, ${HISTORY.length} activity items, SSE ${DEFAULT_RATE}/s)`);
  console.log(`first account: ${accounts[0].id}`);
});
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
