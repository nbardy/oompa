# oompa

![Oompa Loompas building code](docs/resources/oompa-banner.png)

*Getting your ralphs to work together*

---

## The Minimal Idea

From the [Oompa Loompas blog post](notes/2025-01-instant_software_blog_draft.md) — the simplest multi-agent swarm:

**oompa_loompas.sh** (7 lines):
```bash
#!/bin/bash
for w in $(seq 1 ${WORKERS:-3}); do
  (for i in $(seq 1 ${ITERATIONS:-5}); do
    wt=".w${w}-i${i}"
    git cambium add -b "$wt" -name "oompa-$w-$i" -ephemeral "../oompa-$w-$i"
    { echo "Worktree: ../oompa-$w-$i"; cat prompts/worker.md; } | claude -p -
  done) &
done; wait
```

**prompts/worker.md** (3 lines):
```
Goal: Match spec.md
Process: Create/claim tasks in tasks/{pending,in_progress,complete}.md
Method: Isolate changes to your worktree, commit and merge when complete
```

This historical sketch illustrates parallel agents with worktree isolation.
Use the full runner below for guarded integration and Cambium retirement.

---

## The Full Version

This repo has a fleshed out version of the idea. The oompa loompas are organized by a more sophisticated Clojure harness, enabling advanced features:

- **Different worker types** — small models for fast execution, big models for planning
- **Separate review model** — use a smart model to check work before merging
- **Mixed harnesses** — combine Claude, Codex, and Opencode workers in one swarm
- **Self-directed tasks** — workers create and claim tasks from shared folders

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         SELF-DIRECTED WORKERS                        │
│                                                                      │
│   tasks/pending/*.json ──→ Worker claims ──→ tasks/current/*.json   │
│      ▲                         │                                     │
│      │                         ▼                                     │
│      │                    Execute in worktree                        │
│      │                         │                                     │
│      │                         ▼                                     │
│      │                    Commit changes                             │
│      │                         │                                     │
│      │                         ▼                                     │
│      │               ┌─────────────────────┐                         │
│      │               │  REVIEWER checks    │                         │
│      │               │  (review_model)     │                         │
│      │               └──────────┬──────────┘                         │
│      │                    ┌─────┴─────┐                              │
│      │                    ▼           ▼                              │
│      │               Approved     Rejected                           │
│      │                    │           │                              │
│      │                    ▼           └──────┐                       │
│      │               Merge to                │                       │
│      │                 main                  │                       │
│      │                    │                  ▼                       │
│      │                    │            Fix & retry ──→ Reviewer      │
│      │                    │                                          │
│      └─── Create tasks ◄──┘                                          │
│                                                                      │
│   Exit when: __DONE__ token emitted                                 │
└─────────────────────────────────────────────────────────────────────┘
```

### Configuration

**oompa.json** — the only file you need:
```json
{
  "workers": [
    {"model": "claude:opus", "prompt": ["config/prompts/planner.md"], "max_cycle": 5, "count": 1},
    {"model": "codex:gpt-5.3-codex:medium", "prompt": ["config/prompts/executor.md"], "max_cycle": 10, "count": 2, "can_plan": false},
    {"model": "opencode:opencode/kimi-k2.5-free", "prompt": ["config/prompts/executor.md"], "max_cycle": 10, "count": 1, "can_plan": false}
  ]
}
```

This spawns:
- **1 planner** (opus) — reads spec, explores codebase, creates/refines tasks
- **2 codex executors** (gpt-5.3-codex, medium reasoning) — claims and executes tasks fast
- **1 opencode executor** (opencode/kimi-k2.5-free) — same task loop via `opencode run`

#### Worker fields

| Field | Required | Description |
|-------|----------|-------------|
| `model` | yes | `harness:model` or `harness:model:reasoning` (e.g. `codex:gpt-5.3-codex:medium`, `claude:opus`, `opencode:opencode/kimi-k2.5-free`) |
| `prompt` | no | String or array of paths — concatenated into one prompt |
| `max_cycle` | no | Max worker cycles for JSON config runs (default: 10) |
| `count` | no | Number of workers with this config (default: 1) |
| `can_plan` | no | If `false`, worker waits for tasks before starting (default: `true`) |
| `needs_review` | no | If `false`, skip review for this worker even if top-level reviewers are configured (default: `true`) |
| `max_wait_for_tasks` | no | Max seconds a `can_plan: false` worker waits for queue work (default: `600`) |

`oompa loop` still uses the CLI flag `--iterations`. JSON worker configs use `max_cycle`.

Top-level reviewer config also respects `needs_review`. Setting `"needs_review": false` at the top level disables only the generic reviewer block; setting it on a worker disables review for that worker entirely, including inherited top-level reviewers.

#### Composable prompts

`prompt` accepts a string or an array. Arrays get concatenated, so you can reuse a shared base across workers:

```json
{
  "workers": [
    {"model": "claude:opus-4.5", "prompt": ["prompts/base.md", "prompts/architect.md"], "count": 1},
    {"model": "opencode:opencode/kimi-k2.5-free", "prompt": ["prompts/base.md", "prompts/frontend.md"], "count": 2},
    {"model": "codex:codex-5.2-mini", "prompt": ["prompts/base.md", "prompts/backend.md"], "count": 2}
  ]
}
```

Every worker automatically gets task management instructions injected above your prompts. Your prompts just say *what* the worker should do — the framework handles *how* tasks work.

#### Prompt includes

Prompts support `#oompa_directive:include_file "path/to/file.md"` lines.

Use it to share common instructions across roles without copying content.  
Paths are resolved relative to the prompt file containing the directive.

Example:

```md
#oompa_directive:include_file "config/prompts/_agent_scope_rules.md"

You are an executor. Focus on minimal changes and complete tasks.
```

The included file is inlined during prompt load, with a short header noting the injected source.

### Task System

Workers self-organize via the filesystem. Tasks live at the project root and are shared across all worktrees:

```
tasks/
├── pending/*.json    # unclaimed tasks
├── current/*.json    # in progress
└── complete/*.json   # done
```

From inside a worktree, workers reach tasks via `../tasks/`:
- **See tasks**: `ls ../tasks/pending/`
- **Claim**: `mv ../tasks/pending/task.json ../tasks/current/`
- **Complete**: `mv ../tasks/current/task.json ../tasks/complete/`
- **Create**: write new `.json` to `../tasks/pending/`

Task file format:
```json
{
  "id": "task-001",
  "summary": "Add user authentication",
  "description": "Implement JWT-based auth for the API",
  "difficulty": "medium",
  "files": ["src/auth.py", "tests/test_auth.py"],
  "acceptance": ["Login endpoint returns token", "Tests pass"]
}
```

### Bundled Prompts

Three prompt files ship with oompa that you can use in your `prompt` arrays:

| Prompt | Creates Tasks? | Executes Tasks? | Best For |
|--------|----------------|-----------------|----------|
| `config/prompts/worker.md` (default) | yes | yes | General purpose |
| `config/prompts/planner.md` | yes | sometimes | Big models — task design |
| `config/prompts/executor.md` | no | yes | Small/fast models — heads-down work |

---

## Quick Start

```bash
# Clone
git clone https://github.com/nbardy/oompa.git
cd oompa

# Check backends
./swarm.bb check

# Create a spec
echo "Build a simple todo API with CRUD endpoints" > spec.md

# Run the swarm
./swarm.bb run

# Run detached with startup validation (fails fast if startup fails)
./swarm.bb run --detach --config oompa.json
```

## Install (npm)

```bash
# Run without installing globally
npx @nbardy/oompa check
npx @nbardy/oompa swarm
```

```bash
# Or install globally
npm install -g @nbardy/oompa
oompa check
oompa swarm
```

## Commands

```bash
oompa run [file]            # Run from config (defaults: oompa.json, oompa/oompa.json)
oompa run --detach --config oompa.json
oompa swarm [file]          # Direct swarm command (foreground)
oompa list                  # List 20 most recent swarms
oompa list --all            # Full swarm history
oompa view [swarm-id]       # Per-worker runtime status (default: latest swarm)
oompa tasks                 # Show task status
oompa check                 # Check available backends
oompa cleanup               # Remove worktrees
oompa help                  # Show all commands
```

`./swarm.bb ...` works the same when running from a source checkout.

## Disk-efficient worktrees

Oompa requires Cambium on `PATH` and creates each worker with
`git cambium add -b BRANCH -name NAME -ephemeral PATH`. Workspaces have unique
names and live beside the project under `.oompa-worktrees/`, where Cambium
can share files through copy-on-write. Creation fails if Cambium fails.

`oompa worktrees` discovers registered external workspaces. Cleanup uses
`cambium remove NAME` after checking that the owner process is inactive,
the workspace is clean, no Git operation is in progress, and its branch is
integrated into the project checkout. Dirty, active, unmerged, and legacy
unregistered worktrees are preserved; branches are retained. Workers can
retire their own completed workspace. Cambium also carries dependency
folders when supported; follow project bootstrap policy for anything else.

## Opencode Harness

`opencode` workers use one-shot `opencode run --format json` calls with the same worker prompt tagging:

- First prompt line still starts with `[oompa:<swarmId>:<workerId>]`
- `-m/--model` is passed when a worker model is configured
- First iteration starts without `--session`; the worker captures `sessionID` from that exact run output
- On resumed iterations, workers pass `-s/--session <captured-id> --continue`
- Oompa does not call `opencode session list` to guess a "latest" session
- Worker completion markers (`COMPLETE_AND_READY_FOR_MERGE`, `__DONE__`) are evaluated from extracted text events, preserving existing done/merge behavior
- Optional attach mode: set `OOMPA_OPENCODE_ATTACH` (or `OPENCODE_ATTACH`) to add `--attach <url>`

## Worker Conversation Persistence

Workers rely on each CLI's native session persistence (no custom mirror writer):

- Codex: native rollouts under `~/.codex/sessions/YYYY/MM/DD/*.jsonl`
- Claude: native project sessions under `~/.claude/projects/*/*.jsonl`
- Opencode: native session store managed by `opencode`

Oompa still tags the first prompt line with `[oompa:<swarmId>:<workerId>]`
so downstream UIs can identify and group worker conversations.

## Requirements

- Node.js 18+ (only for npm wrapper / npx usage)
- [Babashka](https://github.com/babashka/babashka) (bb)
- Git 2.5+ (for worktrees)
- Cambium (`git cambium` and `cambium`) for managed ephemeral workspaces
- One of:
  - [Claude CLI](https://github.com/anthropics/claude-cli)
  - [Codex CLI](https://github.com/openai/codex)
  - [Opencode CLI](https://github.com/sst/opencode)

## License

MIT

## Buddy-task fork

The `buddies-cambium` branch adds a scoped Buddy task backend. Buddy tasks remain
the authority for ownership, done criteria, blockers and acceptance. Oompa owns
execution, isolated workspaces and candidate review; it does not mirror tasks
into `tasks/`. Existing filesystem mode remains available for upstream projects.

```json
{
  "task_backend": {
    "type": "buddies",
    "workspace_id": "your-workspace-id",
    "buddy_id": "accountable-buddy-id",
    "task_ids": ["task-first", "task-next"],
    "dependencies": {"task-next": ["task-first"]},
    "url_env": "OOMPA_BUDDY_MCP_URL",
    "authorization_env": "AGENT_CLI_MCP_UNLEASHD_BUDDY__AUTHORIZATION"
  },
  "workers": [
    {"model": "codex:gpt-6.1-sol:medium", "max_cycle": 10,
     "max_resumes": 100, "count": 1, "can_plan": false}
  ]
}
```

Start with `./swarm.bb tasks --config oompa.buddies.json` to check the selected
canonical tasks without launching a model. Run from the target project with
`/path/to/oompa/swarm.bb run --config oompa.buddies.json` after that check.
The endpoint and bearer must come from the supported scoped Buddy runtime.
Never save a bearer in configuration, use the owner's HTTP credentials, or open
Buddy database files. Missing, expired or denied authority stops explicitly;
there is no local-queue fallback. A turn-scoped bearer ends with its chat. For unattended work use the Buddy
host's owner-authorized `oompa` launch tool, which creates a separate task-scoped
connection lasting only that run (up to twelve hours). It survives chat completion
and backend replacement; stop, exit and deadline revoke it.

Deadline-bound Cambium workspace creation may take up to ten minutes to
materialize tracked files and dependency layers, clipped to the original run
deadline. Other Cambium/Git queries retain their thirty-second limit. A timeout
retains any partial workspace and owner metadata without fallback or deletion.
Creation resolves the launch checkout's exact HEAD (including a linked checkout),
passes that commit explicitly to Cambium and verifies the resulting HEAD before
starting a model. It never relies on the primary checkout's default branch.
On supported resume, a recorded creation failure before any model/session exists
may retry provisioning once at the same exact canonical claim and original
deadline. Partial directories remain evidence, never assumed valid source. Held
work with model history still requires its original registered workspace and
validator identity; blocked or changed canonical claims are never retried by
this startup repair.

Only explicit task IDs belonging to the configured Buddy and workspace are
admitted. Claims compare the observed revision and preserve the accountable
owner; blocked, paused, in-progress and review tasks are not new work. Configured
`task_ids` order determines selection priority; prerequisite checks still apply.
Explicit prerequisites must be in the admitted task set and actually done. The filesystem
planner is disabled in Buddy mode. Workers receive the full task criteria and
claim results through their prompt rather than finding JSON files themselves.

A merge-ready signal submits the task to **review**, never **done**. Independent
consumer/physics validation and the accountable Buddy still own acceptance.
An empty runnable set with unfinished canonical tasks reports
`awaiting-buddy-acceptance`, not beta completion. After process loss, existing
in-progress tasks stay held until their owner reconciles the original run;
Oompa must not silently recycle another executor's claim.

For deterministic delivery use top-level `task_execution`: `wall_time_seconds`,
`attempt_timeout_seconds`, `validators` keyed by canonical task ID and optional
`prepare_only_task_ids`. A validator contains argv `command`, `timeout_seconds`
and a nonempty `source_files` map of owner-pinned SHA-256 values. The host executes
the source-bound consumer once, verifies its typed verdict and clean source,
fast-forwards the integration checkout, then CAS-accepts the task. Configured code
review runs before the full consumer; its verdict cannot accept physics.

Rejected work keeps its native model session and Cambium source. Held work stays
in the existing run recovery record while independent tasks advance; an empty
runnable set is polled until the shared deadline. Missing validators permit only
explicit preparation/review. When another accepted chunk advances integration,
the selected writer merges its current committed HEAD into a clean registered
candidate from the same recorded starting lineage. It preserves session and
attempt history, invalidates prior readiness/validation, and reruns review and
the gate on the combined source. Merge conflicts remain in the same candidate
for its model to resolve; the framework never resets or rebases that work.
Final completion requires every selected canonical
task actually done, including the parent when it is included as an observed gate.
Workers can return a JSON blocked result with `status: "blocked"`, a nonempty
`reason`, `taskComplete: false`, `readyForMergeValidation: false`, and optional
`requiredOwnerAction`. The framework CAS-holds the canonical task with its reason
and next action before review, retaining its source/session and return history.
Canonical status/revision is also rechecked after every model return. Held work
resumes only after an explicit canonical `open` transition at a new revision;
restarting the run or editing blocked-task metadata does not retry it or renew
the deadline.
Host launches may set `OOMPA_CONTINUATION_CONTROL_DIR` to an existing private
execution journal directory. Before an authorized bounded diagnostic, the host
atomically writes `continuation-request.json` with a nonempty string `id`.
Selected workers acknowledge only when they have no active task, using live state
`status: "waiting"`, `reason: "host-continuation"` and
`execution.host_continuation_id`. Held executions remain intact. The host waits
for every started worker to acknowledge the exact id before running diagnostics,
then removes the request on completion, stop, failure or expiry. Workers refresh
their waiting state and do no task selection until removal; original deadlines
and shutdown still apply. Active tasks continue to their normal checkpoint/hold
without an idle acknowledgement. Invalid requests stop fail closed; without the
environment variable the existing selection behavior is unchanged.
The wave_sim all-beta example lives at `oompa/beta/all.json` in that repository.
Its source, account, physical and human acceptance requirements remain unchanged.
