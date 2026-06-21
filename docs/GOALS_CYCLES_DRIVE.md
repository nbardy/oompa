# Goals, Cycles, and the Budget Cap

The model oompa converged on (2026-06). Three layers, deliberately separate:

```
cycle  →  drive (resume | goal)  →  budget (cap)
```

- **cycle** — the framework's unit of coordination and safety: create worktree, orient,
  claim (atomic arbitration), work, review, serialized merge, recycle, cleanup. One cycle =
  one mergeable proposal. Cycles are *not* optional and are *not* replaced by goals.
- **drive** — how the worker is kept working *inside* a cycle, chosen per harness:
  - `:resume` — oompa owns continuation: run the agent, and re-prompt ("continue working" +
    a single wrap-up nudge near the cap) until it emits a terminal signal. The fallback that
    works for every harness.
  - `:goal` — the harness's own goal mechanism owns continuation (the agent keeps working
    toward a condition without oompa hand-driving each turn).
- **budget** — the per-cycle attempt cap (`max_resumes`). It bounds the work whether the
  drive is `:resume` or `:goal`. Under `:resume` it limits re-prompts; under `:goal` it is the
  **only** thing bounding the loop.

## Why not just unify around goals?

Because a goal is single-agent "keep working," not coordination. It has no worktree isolation,
no atomic claim across racing workers, no review gate, no merge boundary, and — critically —
**no cap**. A goal *without* a cycle around it is a runaway: in testing, a codex goal driven via
`exec resume` ran **20 hours unbounded**, editing a live tree with no review. The cycle is
exactly the machinery (cap + review + merge gate + worktree) that makes a goal safe. So goals
replace the inner *resume loop*, not the *cycle*. "Iterations" as a first-class driver concept is
gone; what remains is the budget, which is just the ceiling.

## The `:drive` seam (per-harness capability)

`agentnet.harness/registry` carries a per-harness `:drive`:

| harness | :drive | meaning |
|---|---|---|
| codex | `:goal` | **documented stub today** — defaults to `:resume` semantics pending a real driving runtime |
| claude / opencode / cursor / gemini* | `:resume` | oompa-driven resume loop |

`run-command!` accepts both the legacy `:resume?` boolean and the new `:drive`, and never drops
either session key (so salvage/resume keep working across the migration). Only the three
worker.clj call sites (run-agent!/run-fix!/run-merge-agent!) thread `:drive`; salvage stays on
`:resume?`.

## Empirical goal findings (what to wire next)

Verified by direct CLI testing (2026-06):

- **Claude `/goal` is the strong candidate.** `claude -p "/goal <condition>"` fires the real
  command (proven: `claude -p "/goal"` returns status at `num_turns:0`, no model turn). It runs a
  **bounded** autonomous loop (~50 turns; returns even if unmet) — i.e. it self-bounds, which is
  exactly what a cycle wants. So a Claude `:goal` worker can be `claude -p "/goal <task acceptance
  + commit + COMPLETE_AND_READY_FOR_MERGE>"` run once, then reviewed/merged.
- **Codex goal is the weak candidate for exec.** The model self-sets a persisted goal via the
  `create_goal` tool, and `codex exec resume` *does* re-engage it — but **unbounded** (the 20h
  run). It must be externally capped; the budget is mandatory, not optional. Keep codex on the
  `:resume` path until that runtime self-terminates.
- **Neither exposes structured external "done" headlessly** — completion stays a text signal, so
  the cycle's `COMPLETE_AND_READY_FOR_MERGE` + review gate remains the source of truth. Compose:
  fold the signal into the goal condition.

## Deprecated

- The legacy `--iterations` CLI flag / `cmd-loop` / `cmd-run-legacy` path is **deprecated** (still
  runs, prints a warning). Config-driven swarms use the JSON `max_cycle` (outer unit) and the
  per-cycle budget; `cycle + budget + goal` replaces the old iteration loop.
