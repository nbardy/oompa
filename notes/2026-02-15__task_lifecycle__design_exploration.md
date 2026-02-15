# Task Lifecycle Design Exploration

Date: 2026-02-15

## Problem

The task header (`_task_header.md`) teaches agents to both create and complete tasks by writing directly to the shared `tasks/` directory. This has two issues:

1. **Unreviewed task creation**: Workers write `.edn` files directly to `tasks/pending/` in the shared root, bypassing the review/merge gate that all other agent output goes through.

2. **Dual authority on completion**: The header tells agents to `mv current→complete`, but the framework also tracks claimed tasks via snapshot diff and annotates metadata in `complete/`. Two systems think they own task completion.

## Current State

The framework already does:
- **Claim detection**: Snapshots `current/` before/after agent runs, diffs to find what was claimed
- **Recycling**: On failure/rejection, moves orphaned tasks `current→pending`
- **Annotation**: After merge, adds `:completed-by`, `:completed-at`, `:merged-commit` to files in `complete/`

But the framework does NOT:
- Move tasks from `current→complete` (agents are told to do this)
- Promote proposed tasks into `pending/` after merge
- Provide any scripts or helpers for claim operations

## Approaches Explored

### Approach A: "Shared Root, Claim Script"
- `claim_task.sh` wraps the `mv pending→current`
- Task creation goes through branch/review
- Con: Agent must understand "create in branch, claim from root" — two different `tasks/pending/` paths
- Con: Planner still writes directly to root (no worktree)

### Approach B: "Remove Create from Header"
- Simplest change: just delete the "Create a new task" section
- Task creation is planner's job only
- Header drops to ~6 lines of actual instruction
- Con: Workers lose ability to self-create discovered sub-tasks

### Approach C: "Full Script Layer"
- Three scripts: `claim_task.sh`, `complete_task.sh`, `list_tasks.sh`
- All in a `bin/` directory
- Con: Over-engineered for what is essentially `mv`
- Con: `OOMPA_BIN` path resolution adds complexity in worktrees

### Approach D: "Thin Header + Propose-in-Branch" (blend)
- `claim.sh` and `complete.sh` live inside `tasks/` (so `{{TASKS_ROOT}}` resolves them)
- Workers propose new tasks by writing `.edn` to `tasks/proposed/` in their branch
- Proposed tasks enter `pending/` after merge/review
- Header is ~10 lines

### Key Insight: Framework Should Own Completion

During exploration of Approach D, we realized the framework already has all the information needed to complete tasks:
- It knows which tasks were claimed (snapshot diff)
- It knows when merge succeeds
- It already annotates metadata in the merge lock

The agent doing `mv current→complete` is redundant and creates dual-authority bugs. If the agent forgets, the task stays in `current/` forever (only recycled on failure, not on success).

## Proposed State Machine

```
            ┌──────────┐
            │ pending  │
            └────┬─────┘
                 │ agent: mv (atomic, can fail if raced)
                 ▼
            ┌──────────┐
       ┌────│ current  │────┐
       │    └──────────┘    │
       │                    │
 merge succeeds       merge fails / error / rejection
       │                    │
       ▼                    ▼
┌──────────┐          ┌──────────┐
│ complete │          │ pending  │  (recycled)
│+metadata │          └──────────┘
└──────────┘
```

Three transitions. Agent owns one (claim). Framework owns two (complete, recycle).

## Transition Ownership

| Transition | Owner | Mechanism | When |
|-----------|-------|-----------|------|
| pending → current | Agent | `mv` (atomic, can fail) | Agent decides to claim |
| current → complete | Framework | `mv` + annotate (under merge lock) | After successful merge |
| current → pending | Framework | `mv` (recycle) | On error, rejection, or no-changes |

## Critique of Current Implementation

1. **Task header contradicts constitution**: Header teaches completion, but principle 7 says framework is authority
2. **`tasks/proposed/` doesn't exist**: Constitution describes it, code doesn't implement it
3. **Planner bypasses review boundary**: Writes directly to `pending/`, auto-commits without review
4. **"Planning vs Executing" section embeds role logic in shared header**: Contradicts planner.md which says "do NOT claim tasks"
5. **No observability into agent intent**: Framework logs outcomes but not what agents tried to do
6. **Claiming race is agent-handled**: `ls` then `mv` has a gap; agents handle race inconsistently

## What the Task Header Should Become (~10 lines)

```markdown
## Tasks
ls {{TASKS_ROOT}}/pending/                    — see tasks
cat {{TASKS_ROOT}}/pending/<file>.edn         — read details
mv {{TASKS_ROOT}}/pending/<file>.edn {{TASKS_ROOT}}/current/<file>.edn  — claim

If mv fails, another worker got it. Pick another.
Propose new tasks: create .edn files in tasks/proposed/ in your branch.
Signal COMPLETE_AND_READY_FOR_MERGE when done.
```

## Implementation Needed

1. **`merge-to-main!`**: After merge success, move claimed tasks `current→complete` (using detected claim set)
2. **`_task_header.md`**: Rewrite to ~10 lines (remove create, complete, planning-vs-executing sections)
3. **`tasks/proposed/`**: Add `.gitkeep`, add framework code to promote `proposed→pending` after merge
4. **`tasks.clj`**: Add `complete-claimed-tasks!` function called from merge path

## Decision

Wrote `docs/SYSTEMS_DESIGN.md` as a principles-only constitution (9 principles).

## Implemented: CLAIM Signal (2026-02-15)

Implemented the proposed state machine with a new CLAIM signal:

**New flow:**
1. Agent reads tasks, outputs `CLAIM(task-001, task-003)` (text signal, like `COMPLETE_AND_READY_FOR_MERGE`)
2. Framework parses CLAIM, does `mv pending→current` for each, resumes agent with results (what succeeded, what was raced, what's left)
3. Agent can CLAIM again if needed, then works
4. Agent signals `COMPLETE_AND_READY_FOR_MERGE`
5. Framework reviews → merges → moves `current→complete` (framework-owned) → annotates metadata

**Files changed:**
- `agent.clj` — `parse-claim-signal` regex parser
- `tasks.clj` — `claim-by-id!`, `claim-by-ids!`, `complete-by-ids!`
- `worker.clj` — `execute-claims!`, CLAIM branch in worker loop, `merge-to-main!` now does `current→complete`, `run-agent!` accepts resume-prompt-override
- `runs.clj` — `:claims` metric in summary
- `_task_header.md` — rewritten from 64→31 lines, teaches CLAIM signal and orient-first flow
- `worker.md` — references CLAIM

**Key design choices:**
- CLAIM is lowest priority signal (DONE > MERGE > CLAIM)
- `claimed-ids` accumulates across iterations within a session, reset when worktree is destroyed
- `detect-claimed-tasks` (snapshot diff) stays as safety net for backward compat with raw `mv`
- One signal per output — mutually exclusive by convention, not enforcement
