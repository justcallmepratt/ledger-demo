#!/usr/bin/env node
// Interaction metrics over the Chrome DevTools Protocol. Node built-ins only (global WebSocket,
// fetch, child_process); the only external piece is a Chrome binary (CHROME_PATH or google-chrome).
//
//   node perf/interactions.mjs --url http://127.0.0.1:4300 [--account-id <uuid>] [--cpu 1]
//        [--nav-cycles 30] [--load-more-runs 5] [--sse-events 1000] [--out results.json]
//
// Measures (all in ms, p50 / p95 / max):
//   nav:<route>      click on a link -> route's data rendered, through the next completed frame
//   sse-steady       SSE event arrival (event.timeStamp) -> its <li> in the /activity feed, +1 frame,
//                    1,000 events at the stream's default ~20/s
//   sse-burst        the same for 1,000 events at 500/s (what a reconnect backlog looks like)
//   load-more        click "Load more" on the dashboard -> 50 more account rows rendered, +1 frame
//   long tasks       main-thread tasks > 50 ms during the SSE runs (a TBT-like cost)
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(`--${n}`);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : d;
};
const BASE = opt('url', 'http://127.0.0.1:4300').replace(/\/$/, '');
const ACCOUNT_ID = opt('account-id', '62a40cf2-52d1-4221-a74f-a8423191ebf1');
const CPU = Number(opt('cpu', 1));
const NAV_CYCLES = Number(opt('nav-cycles', 30));
const LOAD_MORE_RUNS = Number(opt('load-more-runs', 5));
const SSE_EVENTS = Number(opt('sse-events', 1000));
const OUT = opt('out', '');

// ---- stats --------------------------------------------------------------------------------
const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))];
function stats(values) {
  const s = [...values].sort((a, b) => a - b);
  if (s.length === 0) return { n: 0 };
  const r = (x) => Math.round(x * 10) / 10;
  return { n: s.length, p50: r(pct(s, 50)), p95: r(pct(s, 95)), p99: r(pct(s, 99)), max: r(s[s.length - 1]) };
}

// ---- Chrome + CDP -------------------------------------------------------------------------
async function launchChrome() {
  const bin = process.env.CHROME_PATH || 'google-chrome';
  const dir = mkdtempSync(join(tmpdir(), 'ledger-perf-'));
  const proc = spawn(
    bin,
    [
      '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--no-first-run',
      '--disable-extensions', '--mute-audio', '--window-size=1350,940',
      '--remote-debugging-port=0', `--user-data-dir=${dir}`, 'about:blank',
    ],
    { stdio: ['ignore', 'ignore', 'pipe'] },
  );
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const t = setTimeout(() => reject(new Error('Chrome did not start')), 20000);
    proc.stderr.on('data', (d) => {
      buf += d;
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf);
      if (m) {
        clearTimeout(t);
        resolve(m[1]);
      }
    });
    proc.on('exit', (c) => reject(new Error(`Chrome exited early (${c})`)));
  });
  const port = new URL(wsUrl).port;
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.onopen = res;
    ws.onerror = rej;
  });
  let id = 0;
  const pending = new Map();
  const listeners = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(`${msg.error.message}`)) : resolve(msg.result);
    } else if (msg.method) {
      for (const l of listeners.get(msg.method) ?? []) l(msg.params);
    }
  };
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const n = ++id;
      pending.set(n, { resolve, reject });
      ws.send(JSON.stringify({ id: n, method, params }));
    });
  const once = (method) => new Promise((res) => {
    const l = (p) => {
      listeners.set(method, (listeners.get(method) ?? []).filter((x) => x !== l));
      res(p);
    };
    listeners.set(method, [...(listeners.get(method) ?? []), l]);
  });
  const close = () => {
    try { ws.close(); } catch {}
    proc.kill('SIGKILL');
    rmSync(dir, { recursive: true, force: true });
  };
  return { send, once, close };
}

/** Runs `expression` in the page; the expression may return a promise. */
async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
  return r.result.value;
}

// ---- in-page helpers (injected before any app script) -------------------------------------
const PAGE_SCRIPT = `(() => {
  const P = (window.__perf = { arrive: {}, painted: {}, longTasks: [] });
  // 1) Timestamp every SSE 'activity' message at arrival; let the harness pick the stream shape.
  const Native = window.EventSource;
  const query = new URLSearchParams(location.search).get('s');
  window.EventSource = class extends Native {
    constructor(url, init) { super(query ? url + '?' + query : url, init); }
    addEventListener(type, listener, o) {
      if (type !== 'activity') return super.addEventListener(type, listener, o);
      return super.addEventListener(type, (ev) => {
        const m = /#e(\\d{7})/.exec(ev.data);
        if (m) P.arrive[m[1]] = ev.timeStamp;
        return listener.call(this, ev);
      }, o);
    }
  };
  // 2) Long tasks (> 50 ms) on the main thread.
  try {
    new PerformanceObserver((l) => { for (const e of l.getEntries()) P.longTasks.push(e.duration); })
      .observe({ type: 'longtask', buffered: true });
  } catch {}
  // 3) Wait until a predicate holds (checked on every DOM mutation), then through the next full frame.
  P.until = (pred, timeout = 20000) => new Promise((resolve, reject) => {
    const t0 = performance.now();
    let done = false;
    const finish = (fn, v) => { if (!done) { done = true; mo.disconnect(); fn(v); } };
    const check = () => {
      if (done) return;
      let ok = false;
      try { ok = pred(); } catch {}
      if (ok) finish(resolve);
      else if (performance.now() - t0 > timeout) finish(reject, new Error('timeout waiting for predicate'));
    };
    const mo = new MutationObserver(check);
    mo.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true });
    const poll = () => { if (!done) { check(); setTimeout(poll, 50); } };
    check(); poll();
  });
  P.frame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  P.timed = async (action, pred) => {
    const t0 = performance.now();
    action();
    await P.until(pred);
    await P.frame();
    return performance.now() - t0;
  };
  P.text = (sel) => document.querySelector(sel)?.textContent?.trim() ?? '';
  P.count = (sel) => document.querySelectorAll(sel).length;
})();`;

const SEL = {
  accountRows: '[aria-label^="Accounts table"] tbody tr',
  transferRows: '[aria-label^="Transfers table"] tbody tr',
  feedItems: 'ul[aria-label="Activity feed"] li',
  options: 'select option',
};
const NAV = {
  dashboard: { link: 'nav a[href="/"]', ready: `__perf.text('h1') === 'Ledger dashboard' && __perf.count('${SEL.accountRows}') >= 50` },
  activity: { link: 'nav a[href="/activity"]', ready: `__perf.text('h1') === 'Live activity' && __perf.count('${SEL.feedItems}') >= 50` },
  transfer: { link: 'nav a[href="/transfers/new"]', ready: `__perf.text('h1') === 'New transfer' && __perf.count('select option') >= 400` },
  account: { link: `${SEL.accountRows}:first-child th a`, ready: `__perf.text('h1') === 'Alice wallet 000' && __perf.count('${SEL.transferRows}') >= 1` },
};

async function navigate(cdp, path) {
  const loaded = cdp.once('Page.loadEventFired');
  await cdp.send('Page.navigate', { url: BASE + path });
  await loaded;
}
const click = (cdp, spec) =>
  evaluate(cdp, `__perf.timed(() => document.querySelector(${JSON.stringify(spec.link)}).click(), () => ${spec.ready})`);

// ---- scenarios ----------------------------------------------------------------------------
async function routeNavigation(cdp) {
  await navigate(cdp, '/');
  await evaluate(cdp, `__perf.until(() => ${NAV.dashboard.ready})`);
  const samples = { activity: [], transfer: [], dashboard: [], account: [] };
  for (let i = 0; i < NAV_CYCLES + 1; i++) {
    const order = ['activity', 'transfer', 'dashboard', 'account', 'dashboard'];
    for (const key of order) {
      const ms = await click(cdp, NAV[key]);
      if (i > 0) samples[key].push(ms); // cycle 0 warms lazy chunks and JIT
    }
  }
  return Object.fromEntries(Object.entries(samples).map(([k, v]) => [`nav:${k}`, stats(v)]));
}

async function sse(cdp, rate) {
  const q = encodeURIComponent(`rate=${rate}&count=${SSE_EVENTS}`);
  await navigate(cdp, `/activity?s=${q}`);
  // Record, per event, when its row reaches the DOM and the next completed frame.
  await evaluate(cdp, `(async () => {
    const P = __perf;
    await P.until(() => document.querySelector('ul[aria-label="Activity feed"]'));
    P.longTasks.length = 0;
    new MutationObserver((records) => {
      for (const r of records) for (const n of r.addedNodes) {
        if (n.nodeType !== 1) continue;
        const m = /#e(\\d{7})/.exec(n.textContent);
        if (m && P.painted[m[1]] === undefined) {
          P.painted[m[1]] = 0;
          requestAnimationFrame(() => requestAnimationFrame(() => { P.painted[m[1]] = performance.now(); }));
        }
      }
    }).observe(document.querySelector('ul[aria-label="Activity feed"]'), { childList: true });
    return true;
  })()`);
  const expected = SSE_EVENTS;
  const waitMs = (expected / rate) * 1000 + 8000;
  await evaluate(cdp, `__perf.until(() => Object.keys(__perf.arrive).length >= ${expected} - 1 && Object.values(__perf.painted).every((v) => v > 0), ${waitMs})`)
    .catch(() => {});
  await new Promise((r) => setTimeout(r, 500));
  const data = await evaluate(cdp, 'JSON.stringify({ a: __perf.arrive, p: __perf.painted, l: __perf.longTasks })');
  const { a, p, l } = JSON.parse(data);
  const lat = [];
  let missed = 0;
  for (const tag of Object.keys(a)) {
    if (p[tag] > 0) lat.push(p[tag] - a[tag]);
    else missed++;
  }
  const lt = l.filter((d) => d >= 50);
  return {
    stats: { ...stats(lat), received: Object.keys(a).length, notRendered: missed },
    longTasks: { count: lt.length, totalMs: Math.round(lt.reduce((s, d) => s + d, 0)), blockingMs: Math.round(lt.reduce((s, d) => s + d - 50, 0)) },
  };
}

async function loadMore(cdp) {
  const all = [];
  const last = [];
  for (let run = 0; run < LOAD_MORE_RUNS + 1; run++) {
    await navigate(cdp, '/');
    await evaluate(cdp, `__perf.until(() => ${NAV.dashboard.ready})`);
    let rows = 50;
    const clicks = [];
    for (;;) {
      const has = await evaluate(cdp, `[...document.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Load more')`);
      if (!has) break;
      const want = rows + 50;
      const ms = await evaluate(cdp, `__perf.timed(
        () => [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Load more').click(),
        () => __perf.count('${SEL.accountRows}') >= ${want})`);
      rows = want;
      clicks.push(ms);
    }
    if (run > 0) {
      all.push(...clicks);
      last.push(clicks[clicks.length - 1]);
    }
  }
  return { 'load-more': stats(all), 'load-more:last-page': stats(last) };
}

// ---- main ---------------------------------------------------------------------------------
const cdp = await launchChrome();
try {
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PAGE_SCRIPT });
  if (CPU > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: CPU });
  const result = { url: BASE, cpuThrottle: CPU, chrome: (await cdp.send('Browser.getVersion')).product, metrics: {} };
  Object.assign(result.metrics, await routeNavigation(cdp));
  Object.assign(result.metrics, await loadMore(cdp));
  for (const [name, rate] of [['sse-steady', 20], ['sse-burst', 500]]) {
    const r = await sse(cdp, rate);
    result.metrics[name] = r.stats;
    result.metrics[`${name}:long-tasks`] = r.longTasks;
  }
  console.log(JSON.stringify(result, null, 2));
  if (OUT) {
    mkdirSync(dirname(OUT), { recursive: true });
    writeFileSync(OUT, JSON.stringify(result, null, 2));
  }
} finally {
  cdp.close();
}
process.exit(0);
