# Systems Architecture

Oompa is a swarm orchestrator: it launches multiple AI agents in parallel, each working in an isolated git worktree, coordinated through the filesystem. This document describes how the system is structured and how the pieces fit together.

For the design principles governing these decisions, see [SYSTEMS_DESIGN.md](SYSTEMS_DESIGN.md).
For visibility and observability, see [visibility_architecture.md](visibility_architecture.md).

## Terminology

| Term | Definition |
|------|-----------|
| **Swarm** | One invocation of oompa. Launches N workers, optionally a planner and reviewer. Has a unique swarm-id. |
| **Worker** | One agent process running in an isolated git worktree. Identified by worker-id (w0, w1, ...). |
| **Cycle** | One complete unit of work by a worker: orient → claim → code → review → merge. A worker performs multiple cycles during a swarm. Formerly called "iteration." |
| **Review round** | One pass of the reviewer within a cycle. A cycle may have 0 to N review rounds. |
| **Harness** | The CLI tool that runs the agent (claude, codex, opencode, gemini). Each harness has different flags for headless mode, session resume, and model selection. |
| **Signal** | A text string in agent output that the framework parses and reacts to. Agents communicate intent through signals. |

## System Boundary

> **Agents act. The framework reacts.**

Agents are non-deterministic — they read code, make decisions, write changes. The framework is deterministic — it parses signals, moves files, merges branches. The boundary is sharp:

| Agents own | Framework owns |
|-----------|---------------|
| What to work on | Task state transitions (pending → current → complete) |
| How to write code | Git merge to main |
| When to signal done | Worktree creation and cleanup |
| What tasks to claim | Claim execution (atomic file moves) |
| Review feedback | Review loop orchestration |

Agents never need to understand the framework. The framework never needs to understand the agent's reasoning.

## Swarm Structure

A swarm is configured by an `oompa.json` file:

```
oompa.json
├── workers[]         N workers, each with harness, model, max cycles, prompts
├── planner           optional: creates tasks from a spec (runs before workers)
└── reviewer          optional: reviews worker diffs before merge
```

All workers run in parallel. The planner runs first (if configured) to seed `tasks/pending/`. The reviewer is invoked by the framework during each worker's review phase.

## Worker Lifecycle: The Cycle

Each worker performs cycles until it runs out of work or hits its max cycle count. One cycle is one complete work loop:

```
1. WORKTREE    git worktree add .w0-c1 -b oompa/w0-c1
               fresh branch, isolated from other workers and main

2. WORK
   │
   ├─ orient   agent reads spec, git log, ls tasks/pending/
   │           understands the landscape before acting
   │
   ├─ claim    CLAIM(task-001, task-003, task-007)
   │           one signal, multiple task IDs
   │           framework claims all atomically, resumes agent with results
   │
   └─ code     ←──────────────────────────────┐
               agent writes code, runs tests   │
               framework resumes agent          │
               ────────────────────────────────┘
               repeat until agent signals:
                 COMPLETE_AND_READY_FOR_MERGE → step 3
                 __DONE__ → stop worker entirely

3. REVIEW      (up to N rounds, 0 if no reviewer configured)
   │
   ├─ round 1  reviewer examines diff → needs-changes + feedback
   │           agent receives feedback, fixes code
   ├─ ...
   └─ round N  reviewer → approved  → step 4
                         → rejected → recycle tasks, destroy worktree, next cycle

4. MERGE       git merge oompa/w0-c1 into main (serialized across workers)
5. COMPLETE    framework moves claimed tasks current → complete, annotates metadata
6. LOG         framework writes cycle event + review events to runs/{swarm-id}/
7. CLEANUP     git worktree remove, git branch -D
               → next cycle: back to step 1 with .w0-c2
```

### The code loop (step 2, code)

The agent doesn't run once and exit. It runs, produces output, gets resumed by the framework, runs again. This loop continues until the agent emits a terminal signal. The framework's role in this loop is minimal: check for signals, resume if none found.

With persistent sessions (Claude `--resume`, Gemini `--resume latest`, Opencode `--continue`), the agent retains context across resumes within a cycle. Each resume is NOT a fresh start — the agent remembers what it was doing.

### The review loop (step 3)

The reviewer is a separate agent invoked by the framework. It sees the diff and produces a verdict:

- **approved**: work is good, proceed to merge
- **needs-changes**: feedback is sent back to the worker agent, who fixes and resubmits
- **rejected**: after max rounds, work is abandoned. Tasks are recycled back to pending. Worktree is destroyed.

The review loop is bounded (configurable max rounds, typically 3). This prevents infinite fix-reject cycles.

## Signal Protocol

Agents communicate with the framework through text signals in their stdout:

| Signal | Args | Meaning | Framework reaction |
|--------|------|---------|-------------------|
| `CLAIM(id, ...)` | Comma-separated task IDs | Claim pending tasks | Atomically move pending→current, resume agent with results |
| `COMPLETE_AND_READY_FOR_MERGE` | None | Work is done | Enter review loop, then merge |
| `__DONE__` | None | No useful work remains | Stop this worker |

**Rules**:
- One signal per output. Signals are mutually exclusive.
- Priority: `__DONE__` > `COMPLETE_AND_READY_FOR_MERGE` > `CLAIM`
- CLAIM takes multiple IDs in a single call — no need for multiple CLAIM signals.
- Agents that don't emit any signal are resumed (the code loop continues).

## Task State Machine

Tasks are `.edn` files that move through directories:

```
pending/          unclaimed, available for any worker
   │
   │  CLAIM signal → framework does atomic mv
   ▼
current/          claimed by a worker, work in progress
   │
   ├─ merge succeeds → framework moves to complete/, annotates metadata
   │                    (:completed-by, :completed-at, :merged-commit, :review-rounds)
   ▼
complete/         done, merged to main
   │
   └─ merge fails / rejected / error → framework recycles back to pending/
```

The framework is the sole authority on task state. Agents express intent (CLAIM), the framework executes transitions. If two workers race for the same task, one wins atomically (file move), the other gets a clear failure in the CLAIM response.

## Git Isolation Model

Every worker gets its own git worktree — a full checkout on a separate branch:

```
project-root/
├── .w0-c1/          worker w0, cycle 1 (branch: oompa/w0-c1)
├── .w1-c1/          worker w1, cycle 1 (branch: oompa/w1-c1)
├── .w2-c1/          worker w2, cycle 1 (branch: oompa/w2-c1)
└── [main checkout]  shared: tasks/, runs/, config
```

- Workers can't interfere with each other (separate branches, separate directories)
- Workers can't corrupt main (changes only land via merge)
- Workers CAN read shared state (tasks/, spec, git log) from the main checkout
- Merge is serialized — only one worker merges at a time (rebase + merge under lock)

Worktrees are ephemeral. Created at cycle start, destroyed at cycle end (after merge or on failure).

## Session Management

Agent sessions (conversation history, context) are managed by the harness CLI, not by oompa:

| Harness | Session mechanism | Resume command |
|---------|------------------|---------------|
| Claude | `--session-id {uuid}` | `--resume {session-id}` |
| Gemini | Automatic | `--resume latest` |
| Opencode | `-s {id}` | `-s {id} --continue` |
| Codex | None (stateless) | N/A |

Sessions persist across resumes within a cycle (the code loop). When a cycle ends and a new worktree is created, sessions may be reused or reset depending on configuration.

## Full Project Tree

Everything oompa creates or uses in a project:

```
project-root/
│
├── oompa/                              user config (checked in)
│   ├── oompa.json                      swarm configuration
│   └── prompts/                        role prompt files (.md)
│
├── tasks/                              shared task state (on main, checked in)
│   ├── pending/                        unclaimed .edn files
│   ├── current/                        claimed by workers
│   └── complete/                       merged + annotated
│
├── runs/                               event log (gitignored, append-only)
│   └── {swarm-id}/                     one folder per swarm invocation
│       ├── started.json                swarm config + PID
│       ├── stopped.json                stop time + reason
│       ├── cycles/                     per-worker cycle events
│       └── reviews/                    per-review-round events
│
├── .w0-c1/                             ephemeral worktree
├── .w1-c1/                             ephemeral worktree
└── ...
```
