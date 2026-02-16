# Realized State Design

Date: 2026-02-16

## Problem

The run artifact system writes derived state files (`summary.json`, `live-summary.json`) alongside raw event files (`iterations/*.json`, `reviews/*.json`). This creates:

1. **Ghost state**: Derived files outlive their validity. `live-summary.json` persisted after completion, causing dashboards to show dead swarms as "running."
2. **Dual authority**: Two files claim to represent "worker status" — neither is the source of truth, both can be wrong.
3. **Write complexity**: The framework maintains an in-memory atom, a serialization lock, and two write-paths just to keep derived files updated.
4. **Inspection confusion**: An operator sees `live-summary.json` and `summary.json` and has to know which one to trust and when.

## Design Principle

> **Write events. Read projections. Never write projections.**

The only files on disk should be things that actually happened — immutable records of events. Any "summary" or "status" view is computed at read time by whoever needs it. This is the event-sourcing pattern applied to the filesystem coordination protocol.

## What Gets Written

Two levels of events, each append-only/immutable:

### Swarm-level events
```
runs/{swarm-id}/
  started.json    — "started swarm with these workers, this config, at this time"
  stopped.json    — "swarm ended at this time" (written on clean exit)
```

### Worker-level events
```
runs/{swarm-id}/
  iterations/{wid}-i{N}.json       — "worker did iteration N, outcome was X"
  reviews/{wid}-i{N}-r{round}.json — "reviewer said Y about iteration N"
```

That's it. Four file types. All immutable once written.

### Liveness

A swarm is alive if:
- `started.json` exists AND `stopped.json` does not AND the process is alive (PID check)

A swarm is done if:
- `stopped.json` exists (clean exit), OR
- `started.json` exists but process is dead (crashed — recoverable)

No file needs to be deleted. No file can go stale. The state is always consistent.

### PID tracking

`started.json` includes the process PID. Liveness check: `kill -0 <pid>`. No separate meta files needed.

## What Gets Removed

| Artifact | Why |
|----------|-----|
| `live-summary.json` | Derived state, goes stale, caused ghost "running" bug |
| `summary.json` | Derived state, can be computed from iteration files |
| `live-metrics` atom | Only existed to feed `live-summary.json` |
| `live-summary-lock` | Only existed to serialize `live-summary.json` writes |
| `live-summary.schema.json` | No file to validate |
| `summary.schema.json` | No file to validate |

## Read Model (computed on demand)

Any consumer (claude-web-view, CLI `oompa status`, dashboards) computes what it needs:

```
worker status    = latest iteration file's outcome per worker
merges           = count iterations where outcome = "merged"
errors           = count iterations where outcome = "error"
rejections       = count iterations where outcome = "rejected"
total iterations = count iteration files per worker
is running       = started.json exists && !stopped.json && PID alive
```

This is a simple reduce over a directory listing. The iteration files are small JSON — scanning 50-100 of them is trivial.

## Visibility Requirements

An operator with `ls`, `cat`, and `jq` should be able to answer:

1. **Is the swarm running?** → `ls runs/latest/stopped.json` — exists = done, missing = check PID in `started.json`
2. **What's each worker doing?** → `ls runs/latest/iterations/ | sort | tail -n-per-worker` — latest iteration per worker
3. **How many merges?** → `grep -l '"outcome":"merged"' runs/latest/iterations/*.json | wc -l`
4. **What went wrong?** → `grep -l '"outcome":"error"' runs/latest/iterations/*.json` → `cat` the error-snippet
5. **Full history?** → `ls runs/` — each directory is a complete, self-contained run record

No framework source code needed. No schema docs needed. Just files with obvious names.

## Design Goals

1. **No derived state on disk** — events only, projections computed on read
2. **Immutable artifacts** — once written, never modified or deleted
3. **Self-describing** — file names and directory structure tell the story
4. **Swarm lifecycle is two events** — started and stopped, everything between is iteration events
5. **Liveness is a process property** — not a file property. Files record what happened, PIDs say what's happening now.

## What Changes

### oompa (writer)

- `runs.clj`: Remove `write-summary!`, `write-live-summary!`, `update-live-metrics!`, `live-metrics` atom, `live-summary-lock`
- `runs.clj`: Rename `write-run-log!` → `write-started!`, include PID
- `runs.clj`: Add `write-stopped!` — written at clean swarm exit
- `worker.clj`: Remove all `update-live-metrics!` / `write-live-summary!` calls
- `cli.clj`: Call `write-stopped!` in shutdown hook / after swarm loop

### claude-web-view (reader)

- `readLatestOompaRuntime()`: Read `started.json` + scan `iterations/` + check `stopped.json` + PID check
- Remove `readLiveSummaryStatuses()`, `readRunWorkerStatuses()` (derived-state readers)
- Type generation: Remove `live-summary.schema.json`, `summary.schema.json` from schema set

### Schemas

Keep:
- `run.schema.json` → rename to `started.schema.json` (add PID field)
- `iteration.schema.json` (unchanged)
- `review.schema.json` (unchanged)

Add:
- `stopped.schema.json` — `{ swarm-id, stopped-at, reason: "completed"|"interrupted"|"error" }`

Remove:
- `summary.schema.json`
- `live-summary.schema.json`

## Backward Compatibility

Old runs with `summary.json`/`live-summary.json` still work — claude-web-view can check for `started.json` vs `run.json` to know which format it's reading. But we don't optimize for old format; it degrades gracefully.
