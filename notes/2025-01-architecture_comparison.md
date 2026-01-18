# Architecture Comparison: Ralph vs Oompa Loompas

## Ralph Architecture (Simple Loop)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              RALPH LOOP                                      │
│                                                                              │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │                     SINGLE AGENT PROMPT                             │   │
│    │                    (agent_drive.txt)                                │   │
│    │                                                                      │   │
│    │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │   │
│    │   │   EXECUTE   │  │   HANDLE    │  │   UPDATE    │  │  CREATE  │  │   │
│    │   │    TODOs    │  │  BLOCKED    │  │    PLAN     │  │  TODOs   │  │   │
│    │   │    (60%)    │  │   (10%)     │  │   (15%)     │  │  (15%)   │  │   │
│    │   └─────────────┘  └─────────────┘  └─────────────┘  └──────────┘  │   │
│    │                                                                      │   │
│    │                    ONE AGENT DOES EVERYTHING                         │   │
│    └────────────────────────────────────────────────────────────────────┘   │
│                                    │                                         │
│                                    ▼                                         │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │                    SHARED FILESYSTEM STATE                          │   │
│    │                                                                      │   │
│    │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐    │   │
│    │  │ todos/ready/ │  │ todos/       │  │ agent_notes/           │    │   │
│    │  │              │  │ complete/    │  │ (shared memory)        │    │   │
│    │  │ *.md tasks   │  │              │  │                        │    │   │
│    │  └──────────────┘  └──────────────┘  └────────────────────────┘    │   │
│    │                                                                      │   │
│    │  ┌──────────────────────────────────────────────────────────────┐  │   │
│    │  │                      SPEC FILES                               │  │   │
│    │  │  MASTER_DESIGN.md (read-only)  │  ENGINEERING_PLAN.md (r/w)  │  │   │
│    │  └──────────────────────────────────────────────────────────────┘  │   │
│    └────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│                                    │                                         │
│                                    ▼                                         │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │                      SHARED GIT REPO                                │   │
│    │                                                                      │   │
│    │            Agent A ───commit──▶ main ◀──commit─── Agent B          │   │
│    │                        │                   │                         │   │
│    │                  (check status)      (check status)                 │   │
│    │                  (detect conflict)   (skip if conflict)             │   │
│    └────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘

LAUNCHER:
┌──────────────────────────────────────────┐
│  for i in {1..20}; do                    │
│    claude -p < agent_drive.txt           │
│  done                                    │
└──────────────────────────────────────────┘
```

### Ralph's Key Characteristics

| Aspect | Design |
|--------|--------|
| **Agent Count** | 1 (does everything) |
| **Isolation** | None (shared filesystem) |
| **Coordination** | Filesystem locks + git status checks |
| **Review** | Self-review (same agent) |
| **Task System** | TODO files in filesystem |
| **Memory** | agent_notes/ directory |
| **Spec Driving** | MASTER_DESIGN + ENGINEERING_PLAN |
| **Loop** | Simple bash for-loop |
| **Termination** | `DONE! <AGENT_TERMINATE>` signal |

---

## Oompa Loompas Architecture (Parallel Workers)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           OOMPA LOOMPAS SWARM                                │
│                                                                              │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │                         ORCHESTRATOR                                │   │
│    │                    (agentnet/orchestrator.clj)                      │   │
│    │                                                                      │   │
│    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│    │  │ Task Queue   │  │  Worktree    │  │   Merge      │              │   │
│    │  │ (tasks.edn)  │  │    Pool      │  │   Queue      │              │   │
│    │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │   │
│    │         │                 │                 │                       │   │
│    └─────────┼─────────────────┼─────────────────┼───────────────────────┘   │
│              │                 │                 │                            │
│              ▼                 ▼                 ▼                            │
│    ┌─────────────────────────────────────────────────────────────────────┐  │
│    │                      PARALLEL WORKERS                                │  │
│    │                                                                      │  │
│    │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐     │  │
│    │  │    WORKER 0     │  │    WORKER 1     │  │    WORKER 2     │     │  │
│    │  │                 │  │                 │  │                 │     │  │
│    │  │  ┌───────────┐  │  │  ┌───────────┐  │  │  ┌───────────┐  │     │  │
│    │  │  │ WORKTREE  │  │  │  │ WORKTREE  │  │  │  │ WORKTREE  │  │     │  │
│    │  │  │.workers/0 │  │  │  │.workers/1 │  │  │  │.workers/2 │  │     │  │
│    │  │  │ (isolated)│  │  │  │ (isolated)│  │  │  │ (isolated)│  │     │  │
│    │  │  └─────┬─────┘  │  │  └─────┬─────┘  │  │  └─────┬─────┘  │     │  │
│    │  │        │        │  │        │        │  │        │        │     │  │
│    │  │        ▼        │  │        ▼        │  │        ▼        │     │  │
│    │  │  ┌───────────┐  │  │  ┌───────────┐  │  │  ┌───────────┐  │     │  │
│    │  │  │ PROPOSER  │  │  │  │ PROPOSER  │  │  │  │ PROPOSER  │  │     │  │
│    │  │  │(Claude/   │  │  │  │(Claude/   │  │  │  │(Claude/   │  │     │  │
│    │  │  │ Codex)    │  │  │  │ Codex)    │  │  │  │ Codex)    │  │     │  │
│    │  │  └─────┬─────┘  │  │  └─────┬─────┘  │  │  └─────┬─────┘  │     │  │
│    │  │        │        │  │        │        │  │        │        │     │  │
│    │  │        ▼        │  │        ▼        │  │        ▼        │     │  │
│    │  │  ┌───────────┐  │  │  ┌───────────┐  │  │  ┌───────────┐  │     │  │
│    │  │  │ REVIEWER  │  │  │  │ REVIEWER  │  │  │  │ REVIEWER  │  │     │  │
│    │  │  │(Claude/   │  │  │  │(Claude/   │  │  │  │(Claude/   │  │     │  │
│    │  │  │ Codex)    │  │  │  │ Codex)    │  │  │  │ Codex)    │  │     │  │
│    │  │  └─────┬─────┘  │  │  └─────┬─────┘  │  │  └─────┬─────┘  │     │  │
│    │  │        │        │  │        │        │  │        │        │     │  │
│    │  └────────┼────────┘  └────────┼────────┘  └────────┼────────┘     │  │
│    │           │                    │                    │               │  │
│    └───────────┼────────────────────┼────────────────────┼───────────────┘  │
│                │                    │                    │                   │
│                └────────────────────┼────────────────────┘                   │
│                                     ▼                                        │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │                     SEQUENTIAL MERGE                                │   │
│    │                                                                      │   │
│    │   branch/worker-0 ──┐                                               │   │
│    │   branch/worker-1 ──┼──▶ MERGE ──▶ main                            │   │
│    │   branch/worker-2 ──┘   (one at a time)                            │   │
│    │                                                                      │   │
│    │   Conflict? → Resolve or bounce back to proposer                   │   │
│    └────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘

LAUNCHER:
┌──────────────────────────────────────────┐
│  ./swarm.bb run --claude --workers 4     │
│  ./swarm.bb loop 20 --codex              │
└──────────────────────────────────────────┘
```

### Oompa Loompas Key Characteristics

| Aspect | Design |
|--------|--------|
| **Agent Count** | N workers × 2 agents (proposer + reviewer) |
| **Isolation** | Git worktrees (real filesystem per worker) |
| **Coordination** | core.async channels + sequential merge |
| **Review** | Separate reviewer agent (different context) |
| **Task System** | tasks.edn (EDN file) |
| **Memory** | Structured logs (JSONL) + agent_notes/ |
| **Spec Driving** | Policy enforcement (allow/deny globs) |
| **Loop** | Babashka orchestrator with async workers |
| **Termination** | When task queue empty |

---

## Side-by-Side Comparison

```
                    RALPH                           OOMPA LOOMPAS
                    ─────                           ─────────────

COMPLEXITY:         Simple                          Complex
                    ┌─────┐                         ┌─────────────┐
                    │ ▣▣▣ │                         │ ▣▣▣▣▣▣▣▣▣▣▣ │
                    └─────┘                         └─────────────┘

PARALLELISM:        Filesystem-based                Worktree-based
                    ┌───┐ ┌───┐ ┌───┐              ┌───┐ ┌───┐ ┌───┐
                    │ A │ │ B │ │ C │              │ A │ │ B │ │ C │
                    └─┬─┘ └─┬─┘ └─┬─┘              └─┬─┘ └─┬─┘ └─┬─┘
                      │     │     │                  │     │     │
                      └──┬──┴──┬──┘                  │     │     │
                         │     │                     │     │     │
                      ┌──┴─────┴──┐              ┌───┴┐ ┌──┴┐ ┌──┴┐
                      │  SHARED   │              │ WT │ │WT │ │WT │
                      │    FS     │              │  0 │ │ 1 │ │ 2 │
                      └───────────┘              └────┘ └───┘ └───┘

REVIEW:             Self-review                     Separate agent
                    ┌─────────┐                     ┌─────────┐
                    │ Agent   │                     │Proposer │
                    │ reviews │                     └────┬────┘
                    │  own    │                          │
                    │  work   │                          ▼
                    └─────────┘                     ┌─────────┐
                                                   │Reviewer │
                                                   └─────────┘

MERGE:              Direct to main                  Branch → Merge
                    ┌─────┐                         ┌─────┐
                    │ A ──┼──▶ main                 │ A ──┼──▶ branch/A
                    └─────┘                         └─────┘      │
                    ┌─────┐                         ┌─────┐      │
                    │ B ──┼──▶ main                 │ B ──┼──▶ branch/B
                    └─────┘                         └─────┘      │
                    (race!)                                      ▼
                                                            ┌────────┐
                                                            │ MERGE  │
                                                            │ QUEUE  │
                                                            └────────┘
```

---

## The Core Difference: Coordination

### Ralph's Coordination
```
Agent A: "I'm about to commit file.swift"
         ↓
         git status --short
         ↓
         "Only my files? Yes → commit"
         "Other files? → skip, retry later"
```
- **Optimistic**: Assume no conflict, check at commit time
- **Simple**: Just git status checks
- **Risk**: Race conditions possible

### Oompa Loompas Coordination
```
Agent A: Working in .workers/worker-0/
         ↓
         (isolated filesystem)
         ↓
         Commit to branch/worker-0
         ↓
         Wait in merge queue
         ↓
         Orchestrator merges to main (one at a time)
```
- **Pessimistic**: Isolate completely, merge safely
- **Complex**: Worktrees + branches + merge queue
- **Safe**: No race conditions possible

---

## Module Breakdown

### Ralph (1 file)
```
agent_drive.txt (450 lines)
├── Decision Engine
├── Task Execution
├── Conflict Detection
├── Plan Updates
└── TODO Creation
```

### Oompa Loompas (9 files)
```
agentnet/src/agentnet/
├── schema.clj       (140 lines) - Data validators
├── worktree.clj     (300 lines) - Git worktree pool
├── agent.clj        (250 lines) - Claude/Codex abstraction
├── review.clj       (280 lines) - Review loop
├── merge.clj        (320 lines) - Branch merging
├── orchestrator.clj (300 lines) - Main coordination
├── cli.clj          (200 lines) - Command line
├── core.clj         (210 lines) - Context builder
└── notes.clj        (120 lines) - Note helpers
                    ─────────────
                    ~2,100 lines total
```
