# AgentNet Swarm

A multi-agent orchestrator that runs parallel AI coding agents (Claude or Codex) with git worktree isolation, automated code review, and conflict-free merging.

## How It Works

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ORCHESTRATOR                                    │
│                                                                              │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                │
│   │   WORKTREE   │     │   WORKTREE   │     │   WORKTREE   │                │
│   │   worker-0   │     │   worker-1   │     │   worker-2   │                │
│   │  (isolated)  │     │  (isolated)  │     │  (isolated)  │                │
│   └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                │
│          │                    │                    │                         │
│          ▼                    ▼                    ▼                         │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                │
│   │   PROPOSER   │     │   PROPOSER   │     │   PROPOSER   │                │
│   │ (Claude/Codex)│     │ (Claude/Codex)│     │ (Claude/Codex)│                │
│   │  writes code │     │  writes code │     │  writes code │                │
│   └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                │
│          │                    │                    │                         │
│          ▼                    ▼                    ▼                         │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                │
│   │   REVIEWER   │     │   REVIEWER   │     │   REVIEWER   │                │
│   │ (Claude/Codex)│     │ (Claude/Codex)│     │ (Claude/Codex)│                │
│   │reviews & fixes│     │reviews & fixes│     │reviews & fixes│                │
│   └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                │
│          │                    │                    │                         │
│          └────────────────────┼────────────────────┘                         │
│                               ▼                                              │
│                        ┌──────────────┐                                      │
│                        │    MERGER    │                                      │
│                        │  (sequential)│                                      │
│                        │  to main     │                                      │
│                        └──────────────┘                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### The Flow

1. **Task Queue** - Tasks defined in `config/tasks.edn`
2. **Worktree Pool** - Each worker gets an isolated git worktree (real filesystem)
3. **Proposer Agent** - Writes code, runs tests, commits to branch
4. **Reviewer Agent** - Reviews changes, requests fixes or approves
5. **Review Loop** - Proposer fixes issues until reviewer approves (max 5 attempts)
6. **Merge** - Approved changes merged to main (sequential to avoid conflicts)

### Why Worktrees?

Each agent works in a **real filesystem** (not patches):
- Agents can run tests (`pytest`, `npm test`)
- Agents can build and verify their changes
- Git tracks everything on isolated branches
- No conflicts until merge time

## Quick Start

```bash
# Clone and enter
git clone https://github.com/YOUR_USERNAME/codex-swarm.git
cd codex-swarm

# Verify setup
./test_swarm.sh

# Run with Claude (2 parallel workers)
./swarm.bb run --claude --workers 2

# Run with Codex (4 parallel workers)
./swarm.bb run --codex --workers 4

# Run 20 iterations
./swarm.bb loop 20 --claude
```

## Requirements

- [Babashka](https://github.com/babashka/babashka) (bb) - Clojure scripting
- Git 2.5+ (for worktrees)
- One of:
  - [Claude CLI](https://github.com/anthropics/claude-cli) (`claude`)
  - [Codex CLI](https://github.com/openai/codex) (`codex`)

```bash
# Check what's available
./swarm.bb check
```

## Commands

```bash
./swarm.bb run              # Run all tasks once
./swarm.bb run --claude     # Use Claude backend
./swarm.bb run --codex      # Use Codex backend
./swarm.bb run --workers 4  # 4 parallel workers
./swarm.bb run --dry-run    # Don't actually merge

./swarm.bb loop 20          # Run 20 iterations
./swarm.bb loop 5 --claude  # 5 iterations with Claude

./swarm.bb status           # Show last run results
./swarm.bb worktrees        # List worktree status
./swarm.bb cleanup          # Remove all worktrees
./swarm.bb check            # Check agent backends
./swarm.bb help             # Show all commands
```

## Configuration

### Tasks (`config/tasks.edn`)

```clojure
[{:id "feature-001"
  :summary "Add user authentication"
  :targets ["src/auth.py" "tests/test_auth.py"]
  :priority 1}

 {:id "bugfix-002"
  :summary "Fix login timeout issue"
  :targets ["src/session.py"]
  :priority 2}]
```

### Policy (`config/policy.edn`)

```clojure
{:allow ["src/**" "tests/**" "docs/**"]
 :deny ["secrets/**" "**/*.pem" "**/.env*"]
 :limits {:max-lines-added 800
          :max-lines-deleted 800
          :max-files 10
          :max-review-attempts 5}}
```

### Prompts (`config/prompts/`)

- `engineer.md` - Proposer agent prompt
- `reviewer.md` - Reviewer agent prompt
- `cto.md` - Planning/architecture prompt

## Architecture

```
codex-swarm/
├── swarm.bb                 # CLI entry point
├── agentnet.bb              # Legacy entry point
├── config/
│   ├── tasks.edn            # Task queue
│   ├── policy.edn           # Security policy
│   └── prompts/             # Agent prompts
│       ├── engineer.md
│       ├── reviewer.md
│       └── cto.md
├── agentnet/src/agentnet/
│   ├── cli.clj              # Command-line interface
│   ├── orchestrator.clj     # Main coordination loop
│   ├── worktree.clj         # Git worktree pool
│   ├── agent.clj            # Claude/Codex abstraction
│   ├── review.clj           # Propose/review loop
│   ├── merge.clj            # Branch merging
│   ├── schema.clj           # Data validators
│   ├── core.clj             # Context builder
│   └── notes.clj            # Note helpers
├── agent_notes/             # Agent learnings/decisions
├── runs/                    # Run logs (JSONL)
└── .workers/                # Worktree pool (created at runtime)
```

## How Agents Are Launched

### Claude Backend
```bash
claude -p --model opus --dangerously-skip-permissions < prompt.txt
```

### Codex Backend
```bash
codex exec --full-auto --skip-git-repo-check -C .workers/worker-0 --sandbox workspace-write -- "prompt"
```

The orchestrator:
1. Builds context (queue, hotspots, policy)
2. Tokenizes prompt template with context
3. Launches agent in worktree directory
4. Captures output and parses results

## Multi-Agent Coordination

```
Worker 0: task-A ──▶ propose ──▶ review ──▶ ✓ approved ──┐
Worker 1: task-B ──▶ propose ──▶ review ──▶ fix ──▶ review ──▶ ✓ ──┤
Worker 2: task-C ──▶ propose ──▶ review ──▶ ✗ exhausted          │
                                                                  │
                                                    ┌─────────────┘
                                                    ▼
                                              MERGE QUEUE
                                              (sequential)
                                                    │
                                                    ▼
                                                  main
```

- Workers run in parallel (different worktrees)
- Each worker has propose→review→fix loop
- Merges are sequential (prevents conflicts)
- Failed reviews after max attempts = task failed

## Run Logs

Results saved to `runs/run-YYYYMMDD-HHMMSS.jsonl`:

```json
{"task-id":"feature-001","status":"merged","worker-id":"worker-0","review-attempts":1}
{"task-id":"bugfix-002","status":"merged","worker-id":"worker-1","review-attempts":3}
{"task-id":"feature-003","status":"review-exhausted","worker-id":"worker-2","review-attempts":5}
```

## Development

```bash
# Run tests
./test_swarm.sh

# Load modules in REPL
bb -e "(require '[agentnet.orchestrator :as o])"

# Print context (for debugging prompts)
./swarm.bb context
```

## License

MIT
