# From Ralph to Oompa Loompas: Scaling Agent Swarms

Once you have one agent working for you, the obvious question hits: *why not have ten?*

This is the story of how we went from Ralph - a humble for-loop running a single agent - to Oompa Loompas: a coordinated swarm of parallel workers that can chew through codebases at scale.

The journey taught us something important: **parallel execution is easy; coordination is hard.**

---

## The Ralph Loop: Where It All Started

Ralph is embarrassingly simple:

```bash
#!/bin/bash
for i in {1..20}; do
  claude -p < agent_drive.txt
done
```

That's it. Twenty iterations. Each one reads the filesystem, does some work, writes back. The next iteration picks up where the last one left off.

The filesystem IS the memory. The spec file IS the state machine. Git commits ARE the checkpoints.

Ralph works because it embraces constraints:
- **One agent** - no coordination needed
- **Sequential execution** - no race conditions
- **Filesystem as truth** - no database to sync

For single-threaded tasks, Ralph is perfect. But what happens when you want speed?

---

## The Problem with Parallel Agents

The naive approach:

```bash
# Run 10 Ralphs in parallel
for worker in {1..10}; do
  ( for i in {1..20}; do claude -p < agent_drive.txt; done ) &
done
wait
```

Looks reasonable. Works terribly.

**Problem 1: File Collisions**

Worker 1 writes `auth.py`. Worker 2 writes `auth.py`. Worker 2's version wins. Worker 1's code vanishes. Nobody notices until production breaks.

**Problem 2: Agents Can't Run Tests**

Ralph's superpower is running `pytest` to verify its work. But with 10 workers modifying files simultaneously, pytest sees a Frankenstein of partial changes. Tests become meaningless.

**Problem 3: Git Chaos**

Ten workers racing to commit and push. Merge conflicts everywhere. Git history becomes archaeology.

**Problem 4: No Review**

Self-review doesn't work. The same blindspots that created the bug will miss the bug. You need fresh eyes - a different agent.

[DIAGRAM: Parallel Agents Fighting Over Files]

---

## Enter Oompa Loompas

Oompa Loompas adds what parallel Ralphs lack: **coordination**.

The core insight: give each worker its own sandbox, but coordinate at the boundaries.

```
┌──────────────────────────────────────────────────────────────┐
│                    OOMPA LOOMPAS                             │
│                                                              │
│   ┌─────────┐  ┌─────────┐  ┌─────────┐                     │
│   │Worker 1 │  │Worker 2 │  │Worker 3 │   ← Parallel        │
│   │(propose)│  │(propose)│  │(propose)│                     │
│   │(review) │  │(review) │  │(review) │                     │
│   └────┬────┘  └────┬────┘  └────┬────┘                     │
│        │            │            │                           │
│   ┌────┴────┐  ┌────┴────┐  ┌────┴────┐                     │
│   │Worktree │  │Worktree │  │Worktree │   ← Isolated        │
│   │   1     │  │   2     │  │   3     │                     │
│   └────┬────┘  └────┴────┘  └────┬────┘                     │
│        │            │            │                           │
│        └────────────┼────────────┘                          │
│                     ▼                                        │
│              ┌───────────┐                                  │
│              │Merge Queue│                    ← Sequential  │
│              └─────┬─────┘                                  │
│                    ▼                                        │
│               ┌────────┐                                    │
│               │  Main  │                                    │
│               └────────┘                                    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## How It Works

### Git Worktrees: The Isolation Layer

Git worktrees let you have multiple working directories for the same repo:

```bash
# Create isolated workspaces
git worktree add ../worker-1 -b feature/auth main
git worktree add ../worker-2 -b feature/api main
git worktree add ../worker-3 -b feature/ui main
```

Each worker gets:
- Its own directory on disk
- Its own branch
- Full ability to run tests, builds, everything

But they share the `.git` folder - efficient, no duplication.

[DIAGRAM: Git Worktrees Architecture]

Now Worker 1 can run `pytest` without seeing Worker 2's half-written code. Problem 2: solved.

### Proposer/Reviewer Separation

Each worker runs TWO agents:

1. **Proposer**: Generates code based on the spec
2. **Reviewer**: Different agent that reviews and approves/rejects

```python
for attempt in range(3):
    proposal = await proposer_agent(task)
    review = await reviewer_agent(proposal)

    if review.approved:
        return proposal

    # Add feedback, try again
    task.add_context(review.feedback)
```

Why separate agents? Same reason you don't proofread your own novel. Fresh eyes catch what familiar eyes miss.

[DIAGRAM: Proposer-Reviewer Flow]

### The Merge Queue

All the parallel work eventually needs to merge into `main`. This MUST be sequential:

```python
async def merge_queue():
    while True:
        branch = await queue.get()

        async with merge_lock:
            await rebase_onto_main(branch)
            await run_smoke_tests()
            await merge(branch)
```

One at a time. Each merge sees the previous merges. No race conditions.

[DIAGRAM: Sequential Merge Queue]

---

## The Key Insight: Coordination

The difference between "10 Ralphs in parallel" and Oompa Loompas isn't the parallelism. It's the coordination:

| Aspect | Parallel Ralphs | Oompa Loompas |
|--------|----------------|---------------|
| Filesystem | Shared (chaos) | Isolated (worktrees) |
| Review | Self (useless) | Cross-agent |
| Merges | Race condition | Sequential queue |
| Tests | Meaningless | Reliable |

Think of a factory floor. Workers at different stations work in parallel - that's async. But when their work needs to come together on the assembly line, they synchronize - that's sync.

**Most work is async. Merges are sync.**

This pattern - maximize parallel work, minimize synchronized critical sections - is the heart of Oompa Loompas.

---

## Getting Started

### The Minimal Version

Start simple. You can add complexity later.

**Level 1: Just Worktrees**

```bash
# Create worktrees
for i in {1..5}; do
    git worktree add "../wt-$i" -b "task-$i" main
done

# Run workers in parallel
for i in {1..5}; do
    (
        cd "../wt-$i"
        for j in {1..10}; do
            claude -p < task_spec.txt
        done
    ) &
done
wait
```

This gives you isolation. Merge branches manually when done.

**Level 2: Add Sequential Merging**

```bash
LOCK_FILE="/tmp/merge.lock"

merge_safely() {
    (
        flock -x 200
        git checkout main
        git pull
        git merge --no-ff "$1"
        git push
    ) 200>"$LOCK_FILE"
}
```

Now merges are safe. No more race conditions.

**Level 3: Add Review**

```python
async def worker(worktree, task):
    for attempt in range(3):
        await run_proposer(worktree, task)
        result = await run_reviewer(worktree)

        if "APPROVED" in result:
            return True
    return False
```

Now you have quality gates.

### Full Python Orchestrator

For production use, you'll want proper async orchestration:

```python
import asyncio
from pathlib import Path

class OompaLoompas:
    def __init__(self, repo: str, workers: int = 5):
        self.repo = Path(repo)
        self.workers = workers
        self.pool = WorktreePool(repo, workers)
        self.queue = MergeQueue()

    async def run(self, tasks: list[Task]):
        # Start merge processor
        asyncio.create_task(self.queue.process())

        # Run workers in parallel
        async def execute(task):
            wt = self.pool.acquire(task.id)
            worker = Worker(wt, task)
            await worker.execute()
            await self.queue.submit(wt.branch)
            self.pool.release(task.id)

        await asyncio.gather(*[execute(t) for t in tasks])
```

[DIAGRAM: Full Orchestrator Architecture]

---

## What's Next

Oompa Loompas is the foundation. What comes next?

**Smarter Task Distribution**: Instead of round-robin, use ML to match tasks to workers based on past performance.

**Dynamic Scaling**: Add workers when the queue is long, remove them when it's short.

**Cross-Repo Coordination**: Run Oompa Loompas across multiple repositories with dependency awareness.

**Human-in-the-Loop**: Pause for human review on high-risk changes before merge.

**Observability**: Dashboards showing worker status, queue depth, success rates, time-to-merge.

But those are future explorations.

For now, we've solved the core problem: how to scale from one Ralph to many, without losing what made Ralph work in the first place.

---

## The Ralph-to-Oompa-Loompas Journey

| Start Here | Add This | Get This |
|------------|----------|----------|
| Ralph (1 agent) | - | Simplicity, easy debugging |
| + Worktrees | Parallel workers | Speed, isolation |
| + Sequential Merge | Merge queue | Clean git history |
| + Proposer/Reviewer | Quality gates | Higher code quality |
| + Full Orchestration | Pool, retries, conflict resolution | Production-ready |

You don't have to jump to the end. Start with Ralph. Add worktrees when you need speed. Add review when you need quality. Scale up as your needs grow.

The pattern is composable. The pieces fit together. That's the design.

---

*Ralph gave us the loop. Oompa Loompas gave us the swarm.*

*Now go build something.*
