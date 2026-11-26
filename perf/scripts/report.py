#!/usr/bin/env python3
"""Helpers around the k6 summary.json written by perf/k6/ledger.js.

  report.py gate    summary.json              exit 1 unless the run is valid (reconciliation, errors, traffic)
  report.py compare base.json cand.json       Markdown before/after table with p95 deltas
  report.py noise   run1.json run2.json ...   run-to-run spread of p95 per scenario
  report.py samples samples.log               Markdown digest of the host / JVM / PostgreSQL sampler
  report.py pgtop   pg_stat_statements_top.csv  Markdown table of the top statements by total time

Run with `python3 -I` (no user site / cwd imports). Standard library only.
"""
import csv
import json
import re
import sys

SCENARIOS = ["single_transfers", "hot_account", "linked_batches", "two_phase", "reads", "idempotent_retries"]
MAX_ERROR_RATE = 0.01


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def gate(path):
    data = load(path)
    problems = []
    if not data["reconciliation"]["ok"]:
        problems.append("reconciliation did not pass (books unbalanced or no check ran)")
    for name in SCENARIOS:
        s = data["scenarios"][name]
        if s["requests"] == 0:
            problems.append(f"{name}: no requests were measured in the steady phase")
        if s["errorRate"] > MAX_ERROR_RATE:
            problems.append(f"{name}: error rate {s['errorRate'] * 100:.2f}% is above {MAX_ERROR_RATE * 100:.0f}%")
    for problem in problems:
        print(f"::error::{problem}")
    if not problems:
        print("run is valid: reconciliation passed, every scenario carried traffic, errors within bounds")
    return 1 if problems else 0


def pct(base, cand):
    if not base:
        return "n/a"
    return f"{(cand - base) / base * 100:+.1f}%"


def compare(base_path, cand_path):
    base, cand = load(base_path), load(cand_path)
    print("| Scenario | req/s | p50 ms | p95 ms | p99 ms | errors % | p95 change |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for name in SCENARIOS:
        b, c = base["scenarios"][name], cand["scenarios"][name]
        bl, cl = b["latencyMs"], c["latencyMs"]
        print(
            f"| {name} | {b['reqPerSec']} -> {c['reqPerSec']} | {bl['p50']} -> {cl['p50']} | "
            f"{bl['p95']} -> {cl['p95']} | {bl['p99']} -> {cl['p99']} | "
            f"{b['errorRate'] * 100:.3f} -> {c['errorRate'] * 100:.3f} | {pct(bl['p95'], cl['p95'])} |"
        )
    return 0


def noise(paths):
    runs = [load(p) for p in paths]
    print("| Scenario | p95 per run (ms) | mean | spread (max-min)/mean |")
    print("|---|---|---:|---:|")
    for name in SCENARIOS:
        values = [r["scenarios"][name]["latencyMs"]["p95"] for r in runs]
        mean = sum(values) / len(values)
        spread = (max(values) - min(values)) / mean * 100 if mean else 0
        print(f"| {name} | {', '.join(str(v) for v in values)} | {mean:.2f} | {spread:.1f}% |")
    return 0


def stat(values):
    if not values:
        return "n/a"
    return f"avg {sum(values) / len(values):.2f}, max {max(values):.2f}"


def samples(path):
    """Digest of sample.sh output. Covers the whole job (setup and cool-down included)."""
    series = {}
    waits = {}
    n = 0
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("==="):
                n += 1
            elif line.startswith("app: "):
                m = re.match(r"app: (\w+)(?:\{[^}]*\})? ([-0-9.eE+]+)$", line)
                if m:
                    series.setdefault(m.group(1), []).append(float(m.group(2)))
            elif line.startswith("vmstat"):
                parts = line.split(": ", 1)[1].split()
                if len(parts) >= 4 and all(p.replace(".", "").isdigit() for p in parts[:4]):
                    for label, value in zip(("cpu_user", "cpu_sys", "cpu_idle", "cpu_iowait"), parts[:4]):
                        series.setdefault(label, []).append(float(value))
            elif line.startswith("pg_wait: "):
                m = re.match(r"pg_wait: (\S+) x(\d+)$", line)
                if m:
                    waits[m.group(1)] = waits.get(m.group(1), 0) + int(m.group(2))
            elif line.startswith("outbox_backlog: "):
                series.setdefault("outbox_backlog", []).append(float(line.split(": ")[1]))
    print(f"Sampler: {n} samples (5 s apart, whole job)")
    print()
    print("| Signal | avg / max |")
    print("|---|---|")
    for key in ("hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_max",
                "process_cpu_usage", "system_cpu_usage", "jvm_threads_live_threads",
                "cpu_user", "cpu_sys", "cpu_idle", "cpu_iowait", "outbox_backlog"):
        if key in series:
            print(f"| {key} | {stat(series[key])} |")
    total = sum(waits.values()) or 1
    print()
    print("| Active-backend wait event (summed over samples) | share |")
    print("|---|---:|")
    for event, count in sorted(waits.items(), key=lambda kv: -kv[1])[:10]:
        print(f"| {event} | {count / total * 100:.1f}% |")
    return 0


def pgtop(path):
    with open(path, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))[:10]
    print("| calls | total ms | mean ms | max ms | rows | query |")
    print("|---:|---:|---:|---:|---:|---|")
    for r in rows:
        query = r["query"].replace("|", "\\|")[:150]
        print(f"| {r['calls']} | {r['total_ms']} | {r['mean_ms']} | {r['max_ms']} | {r['rows']} | `{query}` |")
    return 0


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    command, args = argv[1], argv[2:]
    if command == "gate":
        return gate(args[0])
    if command == "compare":
        return compare(args[0], args[1])
    if command == "noise":
        return noise(args)
    if command == "samples":
        return samples(args[0])
    if command == "pgtop":
        return pgtop(args[0])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
