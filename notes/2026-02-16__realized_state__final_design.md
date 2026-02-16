# Realized State: Final Design

Date: 2026-02-16

## Core Principle

> **Write events. Read projections. Never write projections.**

Every file on disk records something that happened. No file records a computed view of what happened. Summaries, statuses, and aggregates are computed at read time by whoever needs them.

## Shared Type Contract: JSON Schema (draft-07)

[JSON Schema](https://json-schema.org/) is the open standard that bridges Clojure and TypeScript:

```
oompa_loompas/schemas/*.schema.json    ← source of truth
         │
         ├──→ Clojure (oompa): writes maps that conform to schemas
         │    No codegen needed — Clojure is dynamic, maps are maps.
         │
         └──→ TypeScript (claude-web-view): json-schema-to-typescript
              tools/gen-oompa-types.ts reads schemas, emits:
              shared/src/generated/oompa-types.ts
```

One schema, two languages, zero drift. When a schema changes:
1. Update `schemas/*.schema.json` in oompa
2. Run `tsx tools/gen-oompa-types.ts` in claude-web-view
3. TypeScript compiler catches any mismatches

---

## File Structure

```
runs/
  {swarm-id}/                           ← one folder per swarm run
    started.json                        ← event: swarm began
    stopped.json                        ← event: swarm ended (absent while running)
    iterations/
      {worker-id}-i{N}.json            ← event: worker completed iteration N
    reviews/
      {worker-id}-i{N}-r{round}.json   ← event: reviewer judged iteration N, round R
```

### Why this structure

| Decision | Why |
|----------|-----|
| **One folder per swarm** | Self-contained. `cp -r` a run to share it. `rm -rf` a run to clean it. `ls runs/` to see history. |
| **`started.json` + `stopped.json`** | Two lifecycle events, not one mutable state file. A running swarm has `started.json` but no `stopped.json`. A finished swarm has both. No ambiguity. |
| **`iterations/` subfolder** | Groups the bulk of event files. A 6-worker × 30-iteration swarm produces ~180 iteration files — keeping them in a subfolder keeps the run root scannable. |
| **`reviews/` subfolder** | Same reasoning. Reviews are numerous and naturally grouped. |
| **Filename encodes identity** | `w0-i3.json` tells you worker and iteration without opening the file. `w0-i3-r2.json` adds the review round. `ls | sort` gives chronological order per worker. |
| **No `summary.json`** | Derived state. Compute by reducing over `iterations/`. |
| **No `live-summary.json`** | Derived state. Was the source of ghost "running" bugs. |

### Liveness (is the swarm running right now?)

```
started.json exists?
├── NO  → no swarm ran in this directory
└── YES
    └── stopped.json exists?
        ├── YES → swarm is done. Read stopped.json for reason.
        └── NO  → check PID from started.json
            ├── PID alive → swarm is running
            └── PID dead  → swarm crashed (no clean exit)
```

Liveness is a **process property**, not a file property. `started.json` records the PID. `kill -0 <pid>` answers "is it alive?" No polling files for freshness, no stale state.

---

## Schemas

### 1. `started.schema.json` — Swarm Started Event

**File**: `runs/{swarm-id}/started.json`
**Written**: Once, at swarm start
**Why**: Records what was configured and how to check liveness. This is the "birth certificate" of a swarm run.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/nbardy/oompa_loompas/schemas/started.schema.json",
  "title": "OompaStarted",
  "description": "Swarm started event. Written once at swarm start. File: runs/{swarm-id}/started.json",
  "type": "object",
  "required": ["swarm-id", "started-at", "pid", "config-file", "workers"],
  "properties": {
    "swarm-id": {
      "type": "string",
      "description": "Unique identifier for this swarm run (also the directory name)"
    },
    "started-at": {
      "type": "string",
      "format": "date-time",
      "description": "ISO-8601 instant when the swarm started"
    },
    "pid": {
      "type": "integer",
      "description": "OS process ID of the oompa orchestrator. Used for liveness checks (kill -0)."
    },
    "config-file": {
      "type": "string",
      "description": "Path to the oompa.json config file used"
    },
    "workers": {
      "type": "array",
      "description": "Configured workers (what was requested, not what happened)",
      "items": {
        "type": "object",
        "required": ["id", "harness", "model", "iterations", "can-plan", "prompts"],
        "properties": {
          "id":         { "type": "string", "description": "Worker identifier (w0, w1, ...)" },
          "harness":    { "type": "string", "enum": ["codex", "claude", "opencode", "gemini"] },
          "model":      { "type": "string", "description": "Model identifier (e.g. opus, haiku, gemini-2.5-pro)" },
          "reasoning":  { "type": ["string", "null"], "description": "Reasoning effort level, null if not applicable" },
          "iterations": { "type": "integer", "minimum": 1, "description": "Max iterations configured" },
          "can-plan":   { "type": "boolean", "description": "Whether worker starts immediately (true) or waits for tasks (false)" },
          "prompts":    { "type": "array", "items": { "type": "string" }, "description": "Prompt file paths" }
        },
        "additionalProperties": false
      }
    },
    "planner": {
      "type": ["object", "null"],
      "description": "Planner config, null when no planner is configured",
      "properties": {
        "harness":     { "type": "string", "enum": ["codex", "claude", "opencode", "gemini"] },
        "model":       { "type": "string" },
        "prompts":     { "type": "array", "items": { "type": "string" } },
        "max-pending": { "type": "integer", "minimum": 1 }
      },
      "required": ["harness", "model", "prompts", "max-pending"]
    },
    "reviewer": {
      "type": ["object", "null"],
      "description": "Reviewer config, null when no reviewer is configured",
      "properties": {
        "harness": { "type": "string", "enum": ["codex", "claude", "opencode", "gemini"] },
        "model":   { "type": "string" },
        "prompts": { "type": "array", "items": { "type": "string" } }
      },
      "required": ["harness", "model", "prompts"]
    }
  },
  "additionalProperties": false
}
```

**Diff from old `run.schema.json`**: Added `pid` field. Renamed title to `OompaStarted`. Everything else unchanged.

---

### 2. `stopped.schema.json` — Swarm Stopped Event

**File**: `runs/{swarm-id}/stopped.json`
**Written**: Once, when swarm exits cleanly
**Absent**: While swarm is running, or if it crashed (no clean exit)
**Why**: Marks the end of the swarm lifecycle. Absence is meaningful — if `started.json` exists but `stopped.json` doesn't, either the swarm is still running or it crashed.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/nbardy/oompa_loompas/schemas/stopped.schema.json",
  "title": "OompaStopped",
  "description": "Swarm stopped event. Written once at clean swarm exit. Absence means still running or crashed. File: runs/{swarm-id}/stopped.json",
  "type": "object",
  "required": ["swarm-id", "stopped-at", "reason"],
  "properties": {
    "swarm-id": {
      "type": "string"
    },
    "stopped-at": {
      "type": "string",
      "format": "date-time",
      "description": "ISO-8601 instant when the swarm stopped"
    },
    "reason": {
      "type": "string",
      "enum": ["completed", "interrupted", "error"],
      "description": "completed = all workers finished. interrupted = user Ctrl-C or SIGTERM. error = unrecoverable framework error."
    },
    "error": {
      "type": ["string", "null"],
      "description": "Error message if reason is 'error', null otherwise"
    }
  },
  "additionalProperties": false
}
```

---

### 3. `iteration.schema.json` — Worker Iteration Event

**File**: `runs/{swarm-id}/iterations/{worker-id}-i{N}.json`
**Written**: Once, at the end of each worker iteration
**Why**: The atomic unit of work. Each file records one complete iteration — what the worker did, what happened, how long it took. These are the raw events that all projections are built from.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/nbardy/oompa_loompas/schemas/iteration.schema.json",
  "title": "OompaIterationLog",
  "description": "Worker iteration event. One file per iteration. File: runs/{swarm-id}/iterations/{worker-id}-i{N}.json",
  "type": "object",
  "required": ["worker-id", "iteration", "outcome", "timestamp", "duration-ms", "recycled-tasks", "review-rounds"],
  "properties": {
    "worker-id": {
      "type": "string"
    },
    "iteration": {
      "type": "integer",
      "minimum": 1
    },
    "outcome": {
      "type": "string",
      "enum": ["merged", "rejected", "error", "done", "executor-done", "no-changes", "working"],
      "description": "Terminal outcome of this iteration. merged = code merged to main. rejected = reviewer rejected after max rounds. error = agent process failed. done = agent signaled __DONE__. no-changes = no diff produced. working = iteration ended mid-work (CLAIM in progress)."
    },
    "timestamp": {
      "type": "string",
      "format": "date-time",
      "description": "ISO-8601 instant when the iteration completed"
    },
    "duration-ms": {
      "type": "integer",
      "minimum": 0,
      "description": "Wall-clock duration of the iteration in milliseconds"
    },
    "task-id": {
      "type": ["string", "null"],
      "description": "Primary claimed task ID, or null if no task was claimed"
    },
    "claimed-task-ids": {
      "type": "array",
      "items": { "type": "string" },
      "description": "All task IDs claimed via CLAIM signal during this iteration"
    },
    "recycled-tasks": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Task IDs recycled back to pending (on failure or rejection)"
    },
    "error-snippet": {
      "type": ["string", "null"],
      "description": "First ~200 chars of error output, null if no error"
    },
    "review-rounds": {
      "type": "integer",
      "minimum": 0,
      "description": "Number of review rounds for this iteration"
    }
  },
  "additionalProperties": false
}
```

**Diff from old**: Removed `metrics` field (was cumulative derived state — violates "never write projections"). Added `claimed-task-ids` array for CLAIM signal tracking.

---

### 4. `review.schema.json` — Review Event

**File**: `runs/{swarm-id}/reviews/{worker-id}-i{N}-r{round}.json`
**Written**: Once, after each review round
**Why**: Records the reviewer's judgment. Separate from iteration events because one iteration can have multiple review rounds, and the review content (full output, diff list) is large.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/nbardy/oompa_loompas/schemas/review.schema.json",
  "title": "OompaReviewLog",
  "description": "Review event. One file per review round. File: runs/{swarm-id}/reviews/{worker-id}-i{N}-r{round}.json",
  "type": "object",
  "required": ["worker-id", "iteration", "round", "verdict", "timestamp", "output", "diff-files"],
  "properties": {
    "worker-id": {
      "type": "string"
    },
    "iteration": {
      "type": "integer",
      "minimum": 1
    },
    "round": {
      "type": "integer",
      "minimum": 1,
      "description": "Review round number within this iteration (1-indexed)"
    },
    "verdict": {
      "type": "string",
      "enum": ["approved", "needs-changes", "rejected"],
      "description": "Reviewer verdict"
    },
    "timestamp": {
      "type": "string",
      "format": "date-time"
    },
    "output": {
      "type": ["string", "null"],
      "description": "Full reviewer output, null if process failed before producing output"
    },
    "diff-files": {
      "type": "array",
      "items": { "type": "string" },
      "description": "List of files in the diff under review"
    }
  },
  "additionalProperties": false
}
```

**Unchanged** from current.

---

## Read Model (projections computed on demand)

Any consumer (claude-web-view, `oompa status` CLI, scripts) derives what it needs:

```
┌─────────────────────────┬────────────────────────────────────────────────┐
│ Question                │ How to compute                                │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Is swarm running?       │ started.json exists                           │
│                         │   && !stopped.json                            │
│                         │   && kill -0 <started.pid>                    │
├─────────────────────────┼────────────────────────────────────────────────┤
│ How did it stop?        │ stopped.json → reason field                   │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Worker current status?  │ Latest iteration file per worker → outcome    │
├─────────────────────────┼────────────────────────────────────────────────┤
│ How many merges?        │ count(iterations where outcome == "merged")   │
├─────────────────────────┼────────────────────────────────────────────────┤
│ How many errors?        │ count(iterations where outcome == "error")    │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Is worker exhausted?    │ count(worker iterations) >= started.workers   │
│                         │   .find(id).iterations                        │
├─────────────────────────┼────────────────────────────────────────────────┤
│ What tasks were claimed?│ union of all claimed-task-ids across          │
│                         │   worker's iteration files                    │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Review history?         │ ls reviews/{wid}-i{N}-r*.json                 │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Total duration?         │ stopped.stopped-at - started.started-at       │
│                         │   (or now - started-at if still running)      │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Run history?            │ ls runs/ — each subfolder is a complete run   │
└─────────────────────────┴────────────────────────────────────────────────┘
```

## What Gets Deleted

| Artifact | Was | Why delete |
|----------|-----|-----------|
| `summary.json` | Aggregate written at swarm end | Derived state. Compute from iteration files. |
| `live-summary.json` | Running snapshot written each iteration | Derived state. Caused ghost "running" bug. |
| `summary.schema.json` | Schema for summary.json | No file to validate. |
| `live-summary.schema.json` | Schema for live-summary.json | No file to validate. |
| `run.json` | Config written at start | Replaced by `started.json` (same content + pid). |
| `run.schema.json` | Schema for run.json | Replaced by `started.schema.json`. |
| `live-metrics` atom | In-memory running totals | No live-summary to feed. |
| `live-summary-lock` | Serialization lock | No live-summary to serialize. |
| `write-summary!` | Clojure function | No summary to write. |
| `write-live-summary!` | Clojure function | No live-summary to write. |
| `update-live-metrics!` | Clojure function | No live-metrics to update. |
| `metrics` field in iteration | Cumulative counters per iteration | Derived state embedded in events. |

## Schema File List (final)

```
schemas/
  started.schema.json      ← swarm lifecycle: began
  stopped.schema.json      ← swarm lifecycle: ended
  iteration.schema.json    ← worker event: did work
  review.schema.json       ← reviewer event: judged work
```

Four schemas. Four file types. Each one records a single event that happened once and never changes.
