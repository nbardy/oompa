# Visibility Architecture

How oompa writes, persists, and exposes observable state. Every artifact described here is an immutable event — written once, never modified, never deleted.

For system structure and lifecycle, see [systems_architecture.md](systems_architecture.md).
For design principles, see [SYSTEMS_DESIGN.md](SYSTEMS_DESIGN.md) (especially principle 10: write events, never projections).

## Design Rule

> **Write what happened. Never write what you computed about what happened.**

The framework writes four types of event files. All are append-only and immutable. Summaries, statuses, and aggregate metrics do not exist on disk — they are computed at read time by whatever needs them (dashboards, CLI, scripts).

This eliminates:
- **Ghost state**: derived files outliving their validity (e.g. dashboard showing dead swarms as "running")
- **Dual authority**: two files claiming to represent the same thing
- **Stale reads**: file says one thing, reality says another

## Event Types

Oompa writes exactly four types of events, at two levels:

### Swarm-level events

| Event | File | Written | Records |
|-------|------|---------|---------|
| **Started** | `started.json` | Once, at swarm launch | What was configured, when it began, orchestrator PID |
| **Stopped** | `stopped.json` | Once, at clean swarm exit | When it ended, why (completed / interrupted / error) |

### Worker-level events

| Event | File | Written | Records |
|-------|------|---------|---------|
| **Cycle** | `cycles/{wid}-c{N}.json` | Once, at end of each work cycle | What the worker did: outcome, duration, tasks claimed, errors |
| **Review** | `reviews/{wid}-c{N}-r{round}.json` | Once, per review round | What the reviewer said: verdict, feedback, files reviewed |

Every event is a fact. Facts don't change.

## File Layout

### Per swarm invocation

Each swarm gets its own directory under `runs/`. The swarm-id (8-char hex) is the directory name.

```
runs/
  {swarm-id}/
    started.json                        ← written first
    stopped.json                        ← written last (absent while running)
    cycles/
      w0-c1.json                        ← worker w0, cycle 1
      w0-c2.json                        ← worker w0, cycle 2
      w1-c1.json                        ← worker w1, cycle 1
      ...
    reviews/
      w0-c1-r1.json                     ← worker w0, cycle 1, review round 1
      w0-c1-r2.json                     ← worker w0, cycle 1, review round 2
      w1-c1-r1.json
      ...
```

A complete run directory is self-contained. `cp -r runs/{id} /backup/` captures everything. `ls runs/` shows all historical runs.

### Per cycle (worker work unit)

A cycle produces one cycle event file and zero or more review event files:

```
cycles/w0-c3.json                       the cycle event
reviews/w0-c3-r1.json                   review round 1 (if reviewer configured)
reviews/w0-c3-r2.json                   review round 2 (if needs-changes)
reviews/w0-c3-r3.json                   review round 3 (final verdict)
```

The cycle file records the outcome. The review files record the journey to that outcome. Together they tell the complete story of one unit of work.

### Filename conventions

```
{worker-id}-c{cycle-number}.json            cycle event
{worker-id}-c{cycle-number}-r{round}.json   review event
```

- Worker IDs: positional (`w0`, `w1`) or named (`claude-0`, `opencode-1`)
- Cycle numbers: 1-indexed, sequential per worker
- Review round numbers: 1-indexed, sequential within a cycle
- `ls | sort` gives chronological order per worker

## How Events Are Written

All writes follow the same pattern:

1. **Serialize** to JSON (pretty-printed for human readability)
2. **Write** to a `.tmp` file in the same directory
3. **Rename** `.tmp` to final filename (atomic on POSIX — no partial reads)

This means a reader either sees the complete file or doesn't see it at all. No locks needed. No corruption possible.

Source: `agentnet/src/agentnet/runs.clj`, `write-json!` function.

## Append-Only Log Property

The `runs/` directory is an append-only log:

| Operation | Allowed | Reason |
|-----------|---------|--------|
| Create new file | Yes | Events are appended |
| Read any file | Yes | All files are stable |
| Modify existing file | No | Events are immutable facts |
| Delete a file | No | History is permanent |
| Create new swarm directory | Yes | New swarm = new log segment |

An operator can safely read any file at any time without worrying about consistency windows, partial writes, or race conditions.

The only time files are removed is when an operator explicitly cleans up old runs (`rm -rf runs/{old-swarm-id}`).

## Liveness Detection

Liveness is a **process property**, not a file property. No file's presence or absence determines whether a swarm is alive.

```
started.json exists?
│
├─ NO   → no swarm in this directory
│
└─ YES  → stopped.json exists?
    │
    ├─ YES → swarm is done
    │        read stopped.reason for why (completed / interrupted / error)
    │
    └─ NO  → is PID from started.json alive? (kill -0)
        │
        ├─ YES → swarm is running right now
        │
        └─ NO  → swarm crashed (started but never wrote stopped.json)
                 all state is still valid — cycles written before crash are facts
```

### Why PID, not file polling

Previous design used `live-summary.json` presence as a liveness signal. This caused ghost state: the file persisted after completion, dashboards showed dead swarms as alive. PID checks are authoritative — the OS knows whether a process is alive.

`started.json` records the PID at launch time. Any reader can verify liveness with a single syscall.

## Schema Contract

Schemas are the shared type contract between oompa (Clojure writer) and claude-web-view (TypeScript reader).

### Format

[JSON Schema draft-07](https://json-schema.org/draft-07/json-schema-release-notes) — an open standard, language-agnostic, widely tooled.

### Schema files

```
schemas/
  started.schema.json       validates  runs/{id}/started.json
  stopped.schema.json       validates  runs/{id}/stopped.json
  cycle.schema.json         validates  runs/{id}/cycles/*.json
  review.schema.json        validates  runs/{id}/reviews/*.json
```

Four schemas, one per event type. Each schema is the single source of truth for that event's shape.

### Type generation

```
oompa_loompas/schemas/*.schema.json
         │
         ├── Clojure (oompa)
         │   Writes maps conforming to schemas.
         │   No codegen — Clojure is dynamic, maps are maps.
         │   Validation: manual or via spec if needed.
         │
         └── TypeScript (claude-web-view)
             json-schema-to-typescript compiles schemas to:
             shared/src/generated/oompa-types.ts

             Run: ./server/node_modules/.bin/tsx tools/gen-oompa-types.ts
```

When a schema changes:
1. Update `schemas/*.schema.json` in oompa
2. Update write function in `agentnet/src/agentnet/runs.clj`
3. Run type generator in claude-web-view
4. TypeScript compiler catches any downstream mismatches

## Event Schemas

### Started Event

Records what was configured and how to check liveness.

```json
{
  "swarm-id":    "a38ea6fc",
  "started-at":  "2026-02-16T10:30:00Z",
  "pid":         42195,
  "config-file": "oompa/oompa.json",
  "workers": [
    {
      "id": "w0",
      "harness": "claude",
      "model": "opus",
      "iterations": 30,
      "can-plan": true,
      "prompts": ["oompa/prompts/base.md", "oompa/prompts/architect.md"]
    }
  ],
  "planner":  { "harness": "claude", "model": "opus", ... },
  "reviewer": { "harness": "claude", "model": "opus", ... }
}
```

### Stopped Event

Records when and why the swarm exited.

```json
{
  "swarm-id":   "a38ea6fc",
  "stopped-at": "2026-02-16T14:22:00Z",
  "reason":     "completed",
  "error":      null
}
```

`reason` values:
- `completed` — all workers finished their cycles or signaled `__DONE__`
- `interrupted` — user sent SIGTERM / Ctrl-C
- `error` — unrecoverable framework error (details in `error` field)

### Cycle Event

Records one complete work unit by one worker. This is the core event that all metrics are derived from.

```json
{
  "worker-id":        "w0",
  "cycle":            3,
  "outcome":          "merged",
  "timestamp":        "2026-02-16T11:45:00Z",
  "duration-ms":      185000,
  "claimed-task-ids": ["task-001", "task-003"],
  "recycled-tasks":   [],
  "error-snippet":    null,
  "review-rounds":    2
}
```

`outcome` values:
- `merged` — code was reviewed, approved, and merged to main
- `rejected` — reviewer rejected after max review rounds; tasks recycled
- `error` — agent process failed; tasks recycled
- `no-changes` — agent produced no diff
- `done` — agent signaled `__DONE__` (no more useful work)
- `working` — cycle ended mid-work (framework interrupted)
- `executor-done` — executor received `__DONE__` and reset, ready for next cycle
- `claimed` — worker claimed tasks and is now holding them for work
- `sync-failed` — worker couldn't sync worktree before merge
- `merge-failed` — merge to main failed during cycle
- `interrupted` — worker interrupted by shutdown/signal while running

### Review Event

Records one reviewer judgment within a cycle.

```json
{
  "worker-id":  "w0",
  "cycle":      3,
  "round":      2,
  "verdict":    "approved",
  "timestamp":  "2026-02-16T11:44:30Z",
  "output":     "Changes look good. The error handling covers...",
  "diff-files": ["src/auth.ts", "tests/auth.test.ts"]
}
```

`verdict` values:
- `approved` — proceed to merge
- `needs-changes` — feedback sent back to worker for fixes
- `rejected` — work is abandoned after max rounds

## Read Model

No pre-computed state exists on disk. Consumers derive what they need from raw events.

### Worker status

Read the latest cycle file per worker. The `outcome` field is the current status.

```bash
# Latest cycle for worker w0
ls runs/{id}/cycles/w0-c*.json | sort | tail -1 | xargs cat | jq .outcome
```

### Aggregate metrics

Scan all cycle files for a worker or for the whole swarm.

```bash
# Total merges for the swarm
grep -l '"outcome":"merged"' runs/{id}/cycles/*.json | wc -l

# Errors for worker w1
grep -l '"outcome":"error"' runs/{id}/cycles/w1-c*.json | wc -l
```

### Is worker exhausted?

Compare cycle count against configured max from `started.json`.

```bash
# Configured max for w0
jq '.workers[] | select(.id=="w0") | .iterations' runs/{id}/started.json

# Actual cycles completed
ls runs/{id}/cycles/w0-c*.json | wc -l
```

### Full dashboard projection

For claude-web-view's `/api/swarm-runtime` endpoint, the server:

1. Finds the latest run directory (`ls runs/ | sort by mtime`)
2. Reads `started.json` for worker config and PID
3. Checks `stopped.json` existence for completion status
4. Checks PID liveness if no `stopped.json`
5. Scans `cycles/` for latest outcome per worker
6. Returns a computed `OompaRuntimeSnapshot` (derived type, not on disk)

This projection is computed fresh on every request. No caching, no stale state, no ghost runs.

## What Was Removed

Previous designs included derived state files that caused bugs:

| Removed | Was | Problem |
|---------|-----|---------|
| `summary.json` | Aggregate stats at swarm end | Derived state — duplicate of information in cycle files |
| `live-summary.json` | Running snapshot per iteration | Caused ghost "running" bug — persisted after completion |
| `metrics` field in cycle events | Cumulative counters | Derived state embedded in events — violated immutability |
| `live-metrics` atom | In-memory running totals | Only existed to feed `live-summary.json` |
| `live-summary-lock` | Serialization mutex | Only existed to serialize `live-summary.json` writes |
