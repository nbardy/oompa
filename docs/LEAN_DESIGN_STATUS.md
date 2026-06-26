# Lean Design Alignment — Status & Handoff

Branch: `lean-design-alignment` — 3 commits ahead of `main`, pushed to
`origin/lean-design-alignment`. Tree clean. PR-ready and reviewed clean.

Goal of the arc: make the code match oompa's own stated design
(`SYSTEMS_DESIGN.md` principle 3, "behavior lives in prompts, not code")
without changing the framework lifecycle (worktrees, atomic CLAIM, serialized
merge, recycle, event-not-projection run log).

## Verified at close-out (2026-06-26)

- `git status` clean; local `HEAD` == `origin/lean-design-alignment`
  (`6bdb89e`); 3 commits ahead of `main`, 0 behind.
- Namespaces compile: `bb -e "(require '[agentnet.cli] '[agentnet.worker]
  '[agentnet.harness] '[agentnet.runs] :reload)(println :ok)"` → `:ok`.
- Tests pass on `bb -cp agentnet/src:agentnet/test`:
  `prompt-token-injection` (4 tests / 28 assertions),
  `status-transition` (19 tests / 61 assertions),
  `framework-prompt-golden` (6 tests / 28 assertions) — 0 failures, 0 errors.

## What shipped on this branch (3 commits)

`8ed3eae` — lean design alignment: behavior-in-prompts, knob collapse, :drive seam
- **Prompts out of code (P1/P2):** moved 10 embedded behavioral prompt strings
  out of `worker.clj` into `config/prompts/_framework/*.md`, loaded via one DRY
  helper sharing the `_task_header.md` base-path resolver (resolves from inside
  a worktree). Golden test pins the extracted text byte-for-byte including
  trailing newlines. The `[oompa:swarm:id]` run-identity tag stays in code via
  one helper.
- **Knob collapse (K1):** retired `max_needs_followups` (was unwired) and
  `max_working_resumes` (folded its wrap-up nudge into the single `max_resumes`
  per-cycle budget, firing once on the penultimate attempt). `max_resumes` is
  the one budget; `max_wait_for_tasks` stays the separate queue gate. Old
  configs carrying retired keys are harmless (ignored).
- **:drive seam (D1/D2):** named the resume dispatch as a per-turn
  `:drive {:resume | :goal}` keyword plus a per-harness registry capability
  (codex=`:goal`, others=`:resume`). `run-command!` accepts both legacy
  `:resume?` and `:drive :resume` and drops no session key, so salvage/resume
  keep working. `:goal` is a **documented stub** defaulting to `:resume`
  semantics — the codex `/goal` exec runtime is NOT-READY (exec runs one turn
  then shuts down; no external poll/resume), so no goal loop is half-built.
- **Cycle event (E1):** persisted worktree-path/signals/merge-sha that
  `emit-cycle-log!` already passed but `write-cycle-log!` silently dropped;
  declared all 15 emitted keys in `cycle.schema.json`.
- **Reviewer pacing (R1):** inject last-10 merged subjects (`git log main`)
  into reviewer context plus a redundancy-flagging rubric line.
- **Hygiene:** deleted orphan `cli.clj.bak`; fixed `oompa --help` printing
  "Unknown command"; documented `--iterations` (legacy) vs `max_cycle` (JSON);
  marked `agentnet.bb` legacy.

`a2385aa` — worker/cli: reframe iterations as per-cycle budget cap; deprecate legacy --iterations
- GOALS drive a cycle; the per-cycle attempt budget only CAPS it. The inner
  attempt counter (`iterations`) is the per-cycle budget cap (`max-resumes`),
  not the work driver.
- `worker.clj` ns docstring documents the drive model: `:resume` drive = oompa
  owns continuation via manual re-prompt (nudge), budget caps the chain;
  `:goal` drive (future-real codex `/goal` runtime) = goal loop owns
  continuation, budget is the ONLY cap. `:goal` is still a documented stub
  (turn-drive falls back to `:resume`), so the manual re-prompt is the live
  fallback. Cap behavior unchanged.
- `cli.clj`: deprecated the legacy `--iterations` flag path (parse site +
  cmd-loop / cmd-run-legacy + cmd-help + ns usage). Legacy runtime kept for
  backward compat; now prints a deprecation warning pointing to oompa.json /
  swarm.

`6bdb89e` — docs: goals/cycles/budget model, :drive seam, and goal CLI findings
- Added `docs/GOALS_CYCLES_DRIVE.md` documenting the goals/cycles/budget model,
  the `:drive` seam, and codex goal-CLI findings.

## Deferred (out of scope for this branch)

- **Wire the real Claude `:goal` drive** — this is the design payoff and is
  still a stub. `:goal` currently falls back to `:resume` semantics. The codex
  `/goal` exec runtime is NOT-READY (exec runs one turn then shuts down; no
  external poll/resume), so the goal loop was intentionally left unbuilt rather
  than half-built. When a real goal-driven runtime exists, the goal loop owns
  continuation and the per-cycle budget becomes the only cap.
- **Entry-point merge** — consolidate the entry points.
- **File splits** — `worker.clj` / `cli.clj` split (cli.clj is large).
- **Token-budget dimension** — add a token budget alongside the per-cycle
  attempt budget cap.

## Pre-existing bug found in review (separate fix, NOT part of this branch)

Salvaged workers may run WITHOUT review due to a singular/plural key mismatch
across the started-event boundary:

- `cmd-swarm` writes the started event with `:reviewer-configs`
  (**plural**, a collection) — `cli.clj:1239`.
- `write-started!` destructures `:reviewer-config` (**singular**) —
  `runs.clj:59`. The plural key is never read, so the local is always `nil`.
- `write-started!` then writes `:reviewer` only `(when reviewer-config ...)`
  (`runs.clj:81`), so the `:reviewer` key is omitted from `started.json`.
- The salvage path reads `(:reviewer started)` (`cli.clj:1699`); with the key
  absent it is `nil`, so `reviewers` collapses to `[]` (`cli.clj:1700`) and
  salvaged workers run with no reviewers.

This is a pre-existing defect (not introduced by this branch). It should be
fixed separately — align the key name across `write-started!` / `cmd-swarm`
(and decide singular-vs-collection shape, since `generic-reviewers` is a
collection while the salvage reader expects a single `:reviewer` map). Flagging
here so it is not lost; do not fold it into the lean-design-alignment branch.
