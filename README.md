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
    git worktree add $wt -b $wt 2>/dev/null
    { echo "Worktree: $wt"; cat prompts/worker.md; } | claude -p -
  done) &
done; wait
```

**prompts/worker.md** (3 lines):
```
Goal: Match spec.md
Process: Create/claim tasks in tasks/{pending,in_progress,complete}.md
Method: Isolate changes to your worktree, commit and merge when complete
```

That's it. Parallel agents with worktree isolation.

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
│   tasks/pending/*.edn ──→ Worker claims ──→ tasks/current/*.edn     │
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
    {"model": "claude:opus", "prompt": ["config/prompts/planner.md"], "iterations": 5, "count": 1},
    {"model": "codex:gpt-5.3-codex:medium", "prompt": ["config/prompts/executor.md"], "iterations": 10, "count": 2, "can_plan": false},
    {"model": "opencode:opencode/kimi-k2.5-free", "prompt": ["config/prompts/executor.md"], "iterations": 10, "count": 1, "can_plan": false}
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
| `iterations` | no | Max iterations per worker (default: 10) |
| `count` | no | Number of workers with this config (default: 1) |
| `can_plan` | no | If `false`, worker waits for tasks before starting (default: `true`) |

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

### Task System

Workers self-organize via the filesystem. Tasks live at the project root and are shared across all worktrees:

```
tasks/
├── pending/*.edn     # unclaimed tasks
├── current/*.edn     # in progress
└── complete/*.edn    # done
```

From inside a worktree, workers reach tasks via `../tasks/`:
- **See tasks**: `ls ../tasks/pending/`
- **Claim**: `mv ../tasks/pending/task.edn ../tasks/current/`
- **Complete**: `mv ../tasks/current/task.edn ../tasks/complete/`
- **Create**: write new `.edn` to `../tasks/pending/`

Task file format:
```edn
{:id "task-001"
 :summary "Add user authentication"
 :description "Implement JWT-based auth for the API"
 :difficulty :medium
 :files ["src/auth.py" "tests/test_auth.py"]
 :acceptance ["Login endpoint returns token" "Tests pass"]}
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
./swarm.bb swarm
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
oompa swarm [file]          # Run from oompa.json (default)
oompa tasks                 # Show task status
oompa check                 # Check available backends
oompa cleanup               # Remove worktrees
oompa help                  # Show all commands
```

`./swarm.bb ...` works the same when running from a source checkout.

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
- One of:
  - [Claude CLI](https://github.com/anthropics/claude-cli)
  - [Codex CLI](https://github.com/openai/codex)
  - [Opencode CLI](https://github.com/sst/opencode)

## License

MIT
