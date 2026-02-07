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
- **Mixed harnesses** — combine Claude and Codex workers in one swarm
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

**oompa.json**:
```json
{
  "review_model": "codex:codex-5.2",
  "workers": [
    {"model": "claude:opus-4.5", "iterations": 5, "count": 1, "prompt": "config/prompts/planner.md"},
    {"model": "codex:codex-5.2-mini", "iterations": 10, "count": 3, "prompt": "config/prompts/executor.md"}
  ]
}
```

This spawns:
- **1 planner** (opus) — creates tasks, doesn't write code
- **3 executors** (mini) — claims and executes tasks fast
- **Reviews** done by codex-5.2 before any merge

### Task System

Workers self-organize via filesystem:

```
tasks/
├── pending/*.edn     # unclaimed tasks
├── current/*.edn     # in progress
└── complete/*.edn    # done
```

Workers can:
- **Claim tasks**: `mv pending/task.edn current/`
- **Complete tasks**: `mv current/task.edn complete/`
- **Create tasks**: write new `.edn` to `pending/`

### Prompts

Three built-in worker types:

| Prompt | Role | Creates Tasks? | Executes Tasks? |
|--------|------|----------------|-----------------|
| `worker.md` | Hybrid | ✓ | ✓ |
| `planner.md` | Planner | ✓ | ✗ |
| `executor.md` | Executor | ✗ | ✓ |

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

## Commands

```bash
./swarm.bb swarm [file]     # Run from oompa.json (default)
./swarm.bb tasks            # Show task status
./swarm.bb check            # Check available backends
./swarm.bb cleanup          # Remove worktrees
./swarm.bb help             # Show all commands
```

## Worker Conversation Persistence

If `codex-persist` is available, each worker writes its prompt/response messages
to a per-worker session file for external UIs (for example worker panes in
`claude-web-view`).

- Session ID: random lowercase UUID per worker
- First user message is prefixed with `[oompa]` (worker detection tag)
- CWD passed to `codex-persist` is the worker worktree absolute path

Resolution order for the CLI command:
1. `CODEX_PERSIST_BIN` (if set)
2. `codex-persist` on `PATH`
3. `node ~/git/codex-persist/dist/cli.js`

## Requirements

- [Babashka](https://github.com/babashka/babashka) (bb)
- Git 2.5+ (for worktrees)
- One of:
  - [Claude CLI](https://github.com/anthropics/claude-cli)
  - [Codex CLI](https://github.com/openai/codex)

## License

MIT
