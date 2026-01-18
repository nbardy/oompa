# Oompa Loompas: A Deep Exploration of Multi-Agent Swarm Orchestration

## Table of Contents
1. [The Ralph Foundation](#the-ralph-foundation)
2. [The Parallelization Problem](#the-parallelization-problem)
3. [Why Worktrees?](#why-worktrees)
4. [The Coordination Layer](#the-coordination-layer)
5. [Sync vs Async Moments](#sync-vs-async-moments)
6. [The Simplest Oompa Loompas](#the-simplest-oompa-loompas)
7. [Trade-offs: Ralph vs Oompa Loompas](#trade-offs-ralph-vs-oompa-loompas)

---

## The Ralph Foundation

Before we can understand Oompa Loompas, we need to understand Ralph. Ralph is deceptively simple - so simple that you might dismiss it. That would be a mistake.

### What Is Ralph?

Ralph is a for-loop. That's it. Really.

```bash
#!/bin/bash
# ralph.sh - The simplest agent loop

for i in {1..20}; do
  echo "=== Ralph iteration $i ==="
  claude -p < agent_drive.txt
  sleep 2
done
```

Every iteration, the agent reads the current state of the world (files on disk, a spec, maybe some notes from previous runs), does some work, and writes its outputs back. The next iteration picks up where the last one left off.

### Why Does Ralph Work?

Ralph works because of three key properties:

**1. Filesystem as Memory**

The agent doesn't need a database. The filesystem IS the database. After each iteration:
- Code changes are written to files
- Notes and observations go into markdown files
- The spec file tracks what's done vs remaining
- Git commits create restore points

```
project/
├── src/
│   └── feature.py          # Agent writes code here
├── docs/
│   └── notes.md            # Agent leaves notes for future self
├── spec.md                 # What to build (shrinks as work completes)
└── .ralph/
    └── observations.md     # What the agent learned
```

The next iteration of Ralph sees all of this. It has full context because the context IS the filesystem.

**2. Spec-Driven Execution**

Ralph needs to know what to do. A spec file provides this:

```markdown
# Feature Spec: User Authentication

## Requirements
- [ ] Password hashing with bcrypt
- [ ] JWT token generation
- [ ] Token validation middleware
- [ ] Refresh token flow

## Completed
- [x] User model with email/password fields
- [x] Registration endpoint

## Notes
- Using bcrypt cost factor of 12
- Tokens expire in 1 hour
```

Each iteration, the agent reads the spec, picks the next incomplete item, works on it, and updates the spec. Simple, but effective.

**3. No State Machine Needed**

Traditional orchestrators need complex state machines:

```
IDLE → PLANNING → EXECUTING → REVIEWING → IDLE
         ↓           ↓
      BLOCKED    WAITING_ON_HUMAN
```

Ralph doesn't have states. Each iteration is stateless - it reads the world, acts, writes. If something goes wrong, you just re-run. The filesystem state IS the machine state.

### Ralph's Emergent Behaviors

Something interesting happens when you run Ralph for 20+ iterations. The agent develops emergent behaviors:

**Self-Correction**: Around iteration 5-10, you'll see the agent write things like "I notice the tests are failing. Let me trace back..." It catches its own mistakes.

**Documentation**: The agent starts leaving breadcrumbs - comments in code, notes in markdown, even TODOs for future iterations.

**Incremental Progress**: Rather than trying to do everything at once, Ralph naturally falls into an incremental pattern. Small changes, test, commit, repeat.

### The Ralph Mantra

Ralph embodies a philosophy:

> "Do one thing. Do it well. Leave notes. Repeat."

This is the foundation we're building on. Everything in Oompa Loompas is designed to preserve these properties while adding parallelism and coordination.

---

## The Parallelization Problem

So Ralph works. It's slow (sequential), but it works. The obvious next thought: "What if we ran 10 Ralphs in parallel?"

Let's try it:

```bash
# parallel_ralph.sh - The naive approach

for worker in {1..10}; do
  (
    for i in {1..20}; do
      claude -p < agent_drive.txt
    done
  ) &
done

wait
echo "All workers done!"
```

Run this and watch chaos unfold.

### Problem 1: Filesystem Collisions

All 10 agents are reading and writing the same files:

```
# Timeline of disaster

T=0: Worker 1 reads src/auth.py (100 lines)
T=0: Worker 2 reads src/auth.py (100 lines)
T=1: Worker 1 adds login() function (now 150 lines)
T=1: Worker 2 adds logout() function (now 130 lines)
T=2: Worker 1 writes src/auth.py (150 lines)
T=2: Worker 2 writes src/auth.py (130 lines) ← OVERWRITES WORKER 1!
```

Worker 1's login() function? Gone. Worker 2 never saw it.

This isn't a race condition that happens sometimes - it happens constantly. With 10 workers, you're virtually guaranteed to have overlapping writes.

### Problem 2: Agents Can't Run Tests

Ralph's superpower is that the agent can run tests:

```python
# During Ralph iteration, the agent can do:
$ pytest tests/test_auth.py
...
PASSED: 15/15 tests
```

This works because Ralph's changes are ON DISK. pytest sees them.

But with 10 parallel workers all modifying files simultaneously, what does pytest see? A Frankenstein's monster of partial changes from multiple agents. Tests become meaningless.

```
Worker 3 runs: pytest tests/test_auth.py
- Sees Worker 1's half-written login()
- Sees Worker 5's changes to test fixtures
- Sees Worker 2's deleted helper function
Result: CHAOS
```

The agent literally cannot verify its own work. It's flying blind.

### Problem 3: Git Commit Races

Even if we somehow solved the file collision problem, git has opinions:

```bash
# Worker 1 and Worker 2, same second:

Worker 1: git add . && git commit -m "Add login"
Worker 2: git add . && git commit -m "Add logout"

# What actually happens:
Worker 1: git add .
Worker 2: git add .  # Now includes Worker 1's staged changes!
Worker 1: git commit  # Commits partial state
Worker 2: git commit  # Commits... what exactly?
```

Commits become a jumbled mess of unrelated changes. Git history is destroyed. Good luck debugging later.

### Problem 4: No Review Process

With sequential Ralph, you could add a review step:

```bash
for i in {1..20}; do
  claude -p < proposer.txt   # Generate code
  claude -p < reviewer.txt   # Review and fix
done
```

The reviewer sees what the proposer did. It can catch mistakes, suggest improvements, reject bad changes.

With parallel workers, who reviews whom? If Worker 3 reviews Worker 3's changes, that's self-review - the same blindspots that created the bug will miss the bug.

```
┌─────────────────────────────────────────────────────────────┐
│                    THE REVIEW PROBLEM                        │
│                                                              │
│   SELF-REVIEW (bad):        CROSS-REVIEW (good):            │
│                                                              │
│   Worker 3 ──writes──→ code                                 │
│      ↑                   │                                  │
│      └──reviews─────────┘                                  │
│                                                              │
│   Same agent, same blindspots                               │
│                                                              │
│   Worker 3 ──writes──→ code ──reviewed by──→ Worker 7      │
│                                                              │
│   Fresh eyes catch more                                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Problem 5: Merge Hell

Eventually, all these parallel changes need to merge into `main`. With 10 workers making independent changes, you're looking at:

- Merge conflict probability: ~95%
- Semantic conflicts (code that merges but doesn't work): Even higher
- Debugging merged code: Nightmare

```
     ┌─────────────────────────────────────────────────────┐
     │                   MERGE HELL                         │
     │                                                      │
     │   main ────●────●────●────●────●                    │
     │            │    │    │    │    │                    │
     │   worker1  └────●────●────●────┤                    │
     │   worker2       └────●────●────┤                    │
     │   worker3            └────●────┤  ← Conflict Zone   │
     │   worker4                 └────┤                    │
     │   worker5                      ┤                    │
     │                                │                    │
     │                          MERGE EXPLOSION            │
     │                                                      │
     └─────────────────────────────────────────────────────┘
```

### The Parallelization Paradox

Here's the frustrating part: the individual agents are doing good work! Each worker, in isolation, would produce correct code. The problem isn't the agents - it's the lack of coordination between them.

This is the fundamental insight that leads to Oompa Loompas:

> **Parallel agents need more than parallel execution. They need coordination.**

---

## Why Worktrees?

The first problem to solve: how do we give each agent its own isolated filesystem while still sharing the same repository?

Options considered:
1. **Separate clones**: Works, but expensive (duplicates everything)
2. **Docker containers**: Too heavy, complex setup
3. **Virtual filesystems**: Cool idea, hard to implement
4. **Git worktrees**: Just right

### What Are Git Worktrees?

Git worktrees let you have multiple working directories for a single repository. Each worktree has its own:
- Working directory (files on disk)
- Index (staging area)
- HEAD (current commit/branch)

But they SHARE:
- .git objects (commits, blobs, trees)
- Remote configuration
- Hooks

```
┌────────────────────────────────────────────────────────────────┐
│                    GIT WORKTREES ARCHITECTURE                   │
│                                                                 │
│   /project (main worktree)                                      │
│   ├── .git/                    ← Shared git directory          │
│   │   ├── objects/                                             │
│   │   ├── refs/                                                │
│   │   └── worktrees/           ← Metadata for other worktrees  │
│   │       ├── worker-1/                                        │
│   │       ├── worker-2/                                        │
│   │       └── worker-3/                                        │
│   └── src/                     ← Main's working files          │
│                                                                 │
│   /project-worktrees/worker-1/  ← Worker 1's isolated files    │
│   ├── .git → /project/.git      (symlink back to main)         │
│   ├── src/                                                      │
│   └── tests/                                                    │
│                                                                 │
│   /project-worktrees/worker-2/  ← Worker 2's isolated files    │
│   ├── .git → /project/.git                                     │
│   ├── src/                                                      │
│   └── tests/                                                    │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### Creating Worktrees

```bash
# From main repository
cd /project

# Create a worktree on a new branch
git worktree add ../project-worktrees/worker-1 -b feature/worker-1

# Create another
git worktree add ../project-worktrees/worker-2 -b feature/worker-2

# List all worktrees
git worktree list
# /project                            abc1234 [main]
# /project-worktrees/worker-1         abc1234 [feature/worker-1]
# /project-worktrees/worker-2         abc1234 [feature/worker-2]
```

### Why Worktrees Solve Problem 1 (Filesystem Collisions)

Each worker operates in its own worktree:

```
# Worker 1 (in /project-worktrees/worker-1)
echo "def login():" >> src/auth.py

# Worker 2 (in /project-worktrees/worker-2)
echo "def logout():" >> src/auth.py

# No collision! Different files on disk.
```

Worker 1 and Worker 2 are modifying different physical files. No race conditions.

### Why Worktrees Solve Problem 2 (Test Execution)

Each worktree is a real directory with real files:

```bash
# Worker 3 (in /project-worktrees/worker-3)
cd /project-worktrees/worker-3

# Install dependencies (use cached if using uv)
uv sync

# Run tests - sees ONLY Worker 3's changes
pytest tests/

# Build - sees ONLY Worker 3's code
npm run build
```

The agent can verify its own work because it has a complete, isolated copy of the codebase with only its own changes.

### Why Worktrees Solve Problem 3 (Git Commits)

Each worktree has its own HEAD and staging area:

```bash
# Worker 1
cd /project-worktrees/worker-1
git add src/auth.py
git commit -m "Add login function"

# Worker 2 (simultaneously)
cd /project-worktrees/worker-2
git add src/auth.py
git commit -m "Add logout function"

# Both succeed! Different branches, different commits.
```

No git-level race conditions. Each worker commits to its own branch.

### The Efficiency Angle

You might worry: "Isn't this expensive? 10 copies of the codebase?"

Not with worktrees:

```
Traditional clones:
- 10 clones × 500MB = 5GB disk space
- 10 × full git history stored

Worktrees:
- 1 main repo (~500MB) + 10 worktrees (~50MB each) = ~1GB
- Git objects shared via hardlinks
- History stored once
```

Worktrees are ~5x more efficient than clones for this use case.

### Worktree Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│                    WORKTREE LIFECYCLE                        │
│                                                              │
│   1. CREATE                                                  │
│      git worktree add path -b branch                        │
│                                                              │
│   2. USE                                                     │
│      Agent works in worktree directory                      │
│      Makes changes, runs tests, commits                     │
│                                                              │
│   3. MERGE                                                   │
│      Branch merged to main (after review)                   │
│                                                              │
│   4. CLEANUP                                                 │
│      git worktree remove path                               │
│      git branch -d branch                                   │
│                                                              │
│   5. RECYCLE                                                 │
│      Create new worktree for next task                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### When Worktrees Aren't Enough

Worktrees solve the filesystem isolation problem, but they don't solve:

- Review coordination (who reviews whom?)
- Merge sequencing (what order to merge?)
- Conflict resolution (what happens when branches conflict?)
- Task distribution (who works on what?)

For these, we need the coordination layer - which is what Oompa Loompas provides.

---

## The Coordination Layer

With worktrees handling filesystem isolation, we can focus on the higher-level coordination problems. This is where Oompa Loompas comes in.

### The Oompa Loompas Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                      OOMPA LOOMPAS ARCHITECTURE                      │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                        ORCHESTRATOR                           │  │
│   │  - Manages worktree pool                                      │  │
│   │  - Distributes tasks to workers                              │  │
│   │  - Coordinates review handoffs                               │  │
│   │  - Controls merge queue                                      │  │
│   └──────────────────────────────────────────────────────────────┘  │
│              │                    │                    │             │
│              ▼                    ▼                    ▼             │
│   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │
│   │    WORKER 1      │ │    WORKER 2      │ │    WORKER 3      │   │
│   │ ┌──────────────┐ │ │ ┌──────────────┐ │ │ ┌──────────────┐ │   │
│   │ │   PROPOSER   │ │ │ │   PROPOSER   │ │ │ │   PROPOSER   │ │   │
│   │ │   (claude)   │ │ │ │   (claude)   │ │ │ │   (claude)   │ │   │
│   │ └──────────────┘ │ │ └──────────────┘ │ │ └──────────────┘ │   │
│   │        │         │ │        │         │ │        │         │   │
│   │        ▼         │ │        ▼         │ │        ▼         │   │
│   │ ┌──────────────┐ │ │ ┌──────────────┐ │ │ ┌──────────────┐ │   │
│   │ │   REVIEWER   │ │ │ │   REVIEWER   │ │ │ │   REVIEWER   │ │   │
│   │ │   (claude)   │ │ │ │   (claude)   │ │ │ │   (claude)   │ │   │
│   │ └──────────────┘ │ │ └──────────────┘ │ │ └──────────────┘ │   │
│   └──────────────────┘ └──────────────────┘ └──────────────────┘   │
│              │                    │                    │             │
│              ▼                    ▼                    ▼             │
│   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │
│   │    WORKTREE 1    │ │    WORKTREE 2    │ │    WORKTREE 3    │   │
│   │  /wt/worker-1    │ │  /wt/worker-2    │ │  /wt/worker-3    │   │
│   └──────────────────┘ └──────────────────┘ └──────────────────┘   │
│                                                                      │
│                              │                                       │
│                              ▼                                       │
│                    ┌──────────────────┐                             │
│                    │   MERGE QUEUE    │                             │
│                    │   (sequential)   │                             │
│                    └──────────────────┘                             │
│                              │                                       │
│                              ▼                                       │
│                    ┌──────────────────┐                             │
│                    │      MAIN        │                             │
│                    └──────────────────┘                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Component 1: Worktree Pool Manager

The pool manager handles worktree lifecycle:

```python
class WorktreePool:
    def __init__(self, repo_path: str, pool_size: int = 10):
        self.repo_path = repo_path
        self.pool_size = pool_size
        self.available = []
        self.in_use = {}

    def acquire(self, task_id: str) -> Worktree:
        """Get a worktree for a task"""
        if self.available:
            wt = self.available.pop()
            wt.reset_to_main()
        else:
            wt = self._create_worktree()

        wt.create_branch(f"task/{task_id}")
        self.in_use[task_id] = wt
        return wt

    def release(self, task_id: str):
        """Return worktree to pool after merge"""
        wt = self.in_use.pop(task_id)
        wt.cleanup_branch()
        self.available.append(wt)

    def _create_worktree(self) -> Worktree:
        """Create new worktree if pool not at capacity"""
        name = f"worker-{len(self.in_use) + len(self.available)}"
        path = f"{self.repo_path}-worktrees/{name}"
        # git worktree add {path} -b temp-{uuid}
        return Worktree(path)
```

Key behaviors:
- **Pre-warming**: Create worktrees ahead of time for faster task starts
- **Recycling**: Reuse worktrees rather than recreate (reset branch, same files)
- **Soft limits**: Can exceed pool_size if needed, cleans up later

### Component 2: Proposer/Reviewer Separation

This is crucial for catching mistakes:

```python
class Worker:
    def __init__(self, worktree: Worktree, task: Task):
        self.worktree = worktree
        self.task = task
        self.max_review_attempts = 3

    async def execute(self):
        for attempt in range(self.max_review_attempts):
            # Phase 1: Propose
            proposal = await self.propose()

            # Phase 2: Review (different agent!)
            review = await self.review(proposal)

            if review.approved:
                return proposal

            # Phase 3: Address feedback
            self.task.add_context(review.feedback)

        raise MaxReviewAttemptsExceeded(self.task)

    async def propose(self):
        """Proposer agent generates code"""
        return await claude(
            system=PROPOSER_PROMPT,
            context=self.task.spec,
            cwd=self.worktree.path
        )

    async def review(self, proposal):
        """Different agent reviews the proposal"""
        # Key: This is a SEPARATE agent invocation
        # Different "brain" looking at the same code
        return await claude(
            system=REVIEWER_PROMPT,
            context=f"Review this proposal:\n{proposal}",
            cwd=self.worktree.path
        )
```

Why separate agents?

```
┌─────────────────────────────────────────────────────────────┐
│                WHY SEPARATE PROPOSER/REVIEWER                │
│                                                              │
│   SAME AGENT (self-review):                                 │
│   - Has same context/assumptions                            │
│   - Same blindspots that caused bug will miss bug           │
│   - "I wrote it so it must be right" effect                 │
│                                                              │
│   DIFFERENT AGENT (cross-review):                           │
│   - Fresh context                                           │
│   - Different interpretation of spec                        │
│   - Catches assumption errors                               │
│   - Forces code to be self-explanatory                      │
│                                                              │
│   Real-world analogy:                                       │
│   You don't proofread your own novel                        │
│   You don't audit your own financial records                │
│   Fresh eyes find what familiar eyes miss                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Component 3: Review Loop with Max Attempts

The review loop prevents infinite cycles:

```python
PROPOSER_PROMPT = """
You are implementing a feature. Given the spec and any previous feedback,
write code that:
1. Satisfies the requirements
2. Includes tests
3. Follows project conventions

If you've received review feedback, address each point specifically.
"""

REVIEWER_PROMPT = """
You are reviewing code. Check for:
1. Correctness: Does it do what the spec asks?
2. Tests: Are edge cases covered?
3. Style: Does it follow conventions?
4. Security: Any obvious vulnerabilities?

If you find issues, be specific:
- Quote the problematic code
- Explain what's wrong
- Suggest a fix

If everything looks good, respond with: APPROVED
"""
```

The max_attempts limit (usually 3) prevents:
- Infinite loops where proposer and reviewer disagree
- Edge cases that might be genuinely ambiguous
- Wasted compute on hopeless tasks

### Component 4: Sequential Merge Queue

Even with isolated worktrees, merges must be coordinated:

```python
class MergeQueue:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.lock = asyncio.Lock()

    async def submit(self, branch: str, worktree: Worktree):
        """Submit a branch for merging"""
        await self.queue.put((branch, worktree))

    async def process(self):
        """Process merges one at a time"""
        while True:
            branch, worktree = await self.queue.get()

            async with self.lock:  # Only one merge at a time
                try:
                    # Rebase onto latest main
                    await worktree.rebase_onto_main()

                    # Run tests one more time
                    result = await worktree.run_tests()
                    if not result.passed:
                        raise TestsFailedAfterRebase(branch)

                    # Merge to main
                    await self.merge_to_main(branch)

                except MergeConflict:
                    # Put back in queue for conflict resolution
                    await self.handle_conflict(branch, worktree)
```

Why sequential?

```
┌─────────────────────────────────────────────────────────────┐
│                    WHY SEQUENTIAL MERGES                     │
│                                                              │
│   PARALLEL MERGES (broken):                                 │
│                                                              │
│   Worker 1: git checkout main && git merge feature-1        │
│   Worker 2: git checkout main && git merge feature-2        │
│                                                              │
│   Both start from same main, both try to push...            │
│   One wins, one fails, state becomes inconsistent           │
│                                                              │
│   SEQUENTIAL MERGES (correct):                              │
│                                                              │
│   Queue: [feature-1, feature-2, feature-3]                  │
│                                                              │
│   1. Merge feature-1 → main (main moves forward)            │
│   2. Rebase feature-2 onto new main                         │
│   3. Merge feature-2 → main (main moves forward)            │
│   4. Rebase feature-3 onto new main                         │
│   5. Merge feature-3 → main                                 │
│                                                              │
│   Each merge sees previous merges                           │
│   Conflicts caught at rebase time                           │
│   Main always in consistent state                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Component 5: Conflict Resolution

When rebasing reveals conflicts:

```python
async def handle_conflict(self, branch: str, worktree: Worktree):
    """Handle merge conflicts with agent assistance"""

    # Get conflict details
    conflicts = await worktree.get_conflicts()

    # Option 1: Agent-assisted resolution
    resolution = await claude(
        system=CONFLICT_RESOLVER_PROMPT,
        context=f"""
        Your branch: {branch}
        Conflicts with files from other merges:
        {conflicts}

        Resolve the conflicts by keeping functionality from both sides.
        """
    )

    # Option 2: If resolution fails, requeue with updated context
    if not resolution.success:
        # Add conflict context to task
        self.task.add_context(f"Conflict with: {conflicts}")
        # Put back in work queue (not merge queue)
        await self.work_queue.put(self.task)
```

### Putting It All Together

```python
async def run_oompa_loompas(spec_file: str, num_workers: int = 5):
    """Main orchestration loop"""

    # Initialize
    pool = WorktreePool("./", pool_size=num_workers)
    merge_queue = MergeQueue()
    tasks = parse_spec_into_tasks(spec_file)

    # Start merge processor
    asyncio.create_task(merge_queue.process())

    # Process tasks
    async def worker(task):
        wt = pool.acquire(task.id)
        try:
            worker = Worker(wt, task)
            await worker.execute()
            await merge_queue.submit(wt.branch, wt)
        finally:
            pool.release(task.id)

    # Run workers in parallel
    await asyncio.gather(*[worker(t) for t in tasks])
```

---

## Sync vs Async Moments

One of the key insights in Oompa Loompas is understanding when to synchronize and when to run in parallel.

### The Factory Analogy

Imagine a factory floor:

```
┌─────────────────────────────────────────────────────────────┐
│                      FACTORY FLOOR                           │
│                                                              │
│   [Station A]    [Station B]    [Station C]                 │
│       │              │              │                        │
│       ▼              ▼              ▼                        │
│   ┌───────┐      ┌───────┐      ┌───────┐                   │
│   │Worker │      │Worker │      │Worker │  ← ASYNC          │
│   │  1    │      │  2    │      │  3    │    (parallel)     │
│   └───┬───┘      └───┬───┘      └───┬───┘                   │
│       │              │              │                        │
│       └──────────────┴──────────────┘                       │
│                      │                                       │
│                      ▼                                       │
│              ┌───────────────┐                              │
│              │   ASSEMBLY    │  ← SYNC                      │
│              │    LINE       │    (one at a time)           │
│              └───────────────┘                              │
│                      │                                       │
│                      ▼                                       │
│              ┌───────────────┐                              │
│              │   SHIPPING    │                              │
│              └───────────────┘                              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

Workers at stations A, B, C work in parallel. They don't need to wait for each other.

But the assembly line is sequential. Parts arrive one at a time. If you tried to push two parts simultaneously, they'd collide.

Oompa Loompas is the same:

- **Async moments**: Coding, testing, reviewing within a worktree
- **Sync moments**: Merging to main

### Async Moments (Maximize Parallelism)

These operations are safe to parallelize:

```python
# All workers can do these simultaneously
async def async_work(worktree):
    # Reading files - no conflicts between worktrees
    await worktree.read("src/auth.py")

    # Writing files - isolated to this worktree
    await worktree.write("src/auth.py", new_content)

    # Running tests - isolated environment
    await worktree.run("pytest tests/")

    # Committing - to this worktree's branch
    await worktree.commit("Add auth feature")

    # AI generation - stateless, parallelizable
    await claude(prompt, cwd=worktree.path)
```

### Sync Moments (Stop and Coordinate)

These require synchronization:

```python
# Only one at a time
async def sync_work():
    async with merge_lock:
        # Rebase must see latest main
        await worktree.rebase_onto_main()

        # Merge changes state that other workers need
        await main.merge(worktree.branch)

        # Push makes changes visible to all
        await main.push()
```

### The Handoff Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                     HANDOFF PATTERN                          │
│                                                              │
│   Time →                                                     │
│                                                              │
│   Worker 1: ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░            │
│   Worker 2: ░░░░████████░░░░░░░░░░░░░░░░░░░░░░░░            │
│   Worker 3: ░░░░░░░░████████░░░░░░░░░░░░░░░░░░░░            │
│                                                              │
│   Merge:    ░░░░░░░░░░░░░░░░▓▓▓▓░░░░░░░░░░░░░░░            │
│                              ↑                               │
│                        Sequential merge window               │
│                                                              │
│   After rebase, next batch:                                 │
│                                                              │
│   Worker 1: ░░░░░░░░░░░░░░░░░░░░████████░░░░░░░░            │
│   Worker 2: ░░░░░░░░░░░░░░░░░░░░░░░░████████░░░░            │
│                                                              │
│   Legend: ████ = async work, ▓▓▓▓ = sync (merge)            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Minimizing Sync Time

The key to performance is minimizing time in sync sections:

```python
# BAD: Long sync section
async with merge_lock:
    await run_full_test_suite()      # 5 minutes
    await fix_any_failures()         # Variable
    await rebase_onto_main()         # 30 seconds
    await merge()                    # 5 seconds

# GOOD: Minimal sync section
await run_full_test_suite()          # Done in async phase
await self_check_quality()           # Done in async phase

async with merge_lock:
    await rebase_onto_main()         # 30 seconds
    await run_smoke_tests_only()     # 30 seconds
    await merge()                    # 5 seconds
```

Do as much as possible in async phases. Only synchronize for operations that truly require seeing the latest state.

---

## The Simplest Oompa Loompas

Now that we understand the full architecture, let's work backwards. What's the MINIMUM viable Oompa Loompas?

### Level 0: Just Worktrees + Parallel Loops

```bash
#!/bin/bash
# minimal_oompa.sh

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
        git push origin "task-$i"
    ) &
done

wait

# Merge manually later
echo "All workers done. Merge branches manually."
```

This gives you:
- Filesystem isolation
- Parallel execution
- Individual branches

Missing:
- Automated review
- Merge coordination
- Conflict resolution

Verdict: **Good for exploration, bad for production**

### Level 1: Add Sequential Merging

```bash
#!/bin/bash
# level1_oompa.sh

LOCK_FILE="/tmp/merge.lock"

merge_with_lock() {
    local branch=$1

    # Acquire lock (bash flock)
    (
        flock -x 200

        git checkout main
        git pull origin main
        git merge --no-ff "$branch"
        git push origin main

    ) 200>"$LOCK_FILE"
}

# ... parallel work ...

# Sequential merge
for i in {1..5}; do
    merge_with_lock "task-$i"
done
```

Adds:
- Sequential merging
- No merge race conditions

Missing:
- Review process
- Conflict resolution
- Task distribution

Verdict: **Better, but still fragile**

### Level 2: Add Proposer/Reviewer

```python
#!/usr/bin/env python3
# level2_oompa.py

import subprocess
import asyncio

async def worker(worktree, task):
    for attempt in range(3):
        # Propose
        subprocess.run([
            "claude", "-p",
            "--system", "You are implementing a feature...",
        ], cwd=worktree)

        # Review
        result = subprocess.run([
            "claude", "-p",
            "--system", "Review the changes in this directory...",
        ], cwd=worktree, capture_output=True)

        if "APPROVED" in result.stdout.decode():
            return True

    return False

async def main():
    workers = [
        worker(f"../wt-{i}", f"task-{i}")
        for i in range(5)
    ]
    await asyncio.gather(*workers)
```

Adds:
- Separate proposer/reviewer agents
- Max retry limit

Verdict: **Now we're getting somewhere**

### Level 3: Full Orchestration

See the full architecture in previous sections. This adds:
- Worktree pool management
- Proper async/await patterns
- Conflict resolution
- Retry with context
- Proper logging and monitoring

### Which Level Do You Need?

```
┌─────────────────────────────────────────────────────────────┐
│              CHOOSING YOUR OOMPA LOOMPAS LEVEL              │
│                                                              │
│   LEVEL 0 (worktrees only):                                 │
│   - Experimenting with multi-agent                          │
│   - Manual oversight is fine                                │
│   - Low stakes / exploratory work                          │
│                                                              │
│   LEVEL 1 (+ sequential merge):                             │
│   - Need clean git history                                  │
│   - Willing to manually review                              │
│   - Moderate complexity projects                            │
│                                                              │
│   LEVEL 2 (+ proposer/reviewer):                            │
│   - Want automated quality gates                            │
│   - Can't manually review all output                        │
│   - Higher stakes projects                                  │
│                                                              │
│   LEVEL 3 (full orchestration):                             │
│   - Production use                                          │
│   - Complex multi-file changes                              │
│   - Need conflict resolution                                │
│   - Want metrics and observability                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### The Incremental Path

You don't have to build Level 3 immediately. Start simple:

1. Start with Level 0, get familiar with worktrees
2. Add sequential merging when you hit merge conflicts
3. Add review when you catch quality issues
4. Add full orchestration when you need scale

Each level builds on the previous. The patterns are composable.

---

## Trade-offs: Ralph vs Oompa Loompas

Let's be honest about the trade-offs. Oompa Loompas isn't always better.

### Complexity

```
┌─────────────────────────────────────────────────────────────┐
│                      COMPLEXITY COMPARISON                   │
│                                                              │
│   RALPH:                                                     │
│   - One file (10-20 lines)                                  │
│   - No dependencies beyond claude CLI                       │
│   - Debuggable with "echo" and "sleep"                      │
│   - Fails loudly, obviously                                 │
│                                                              │
│   OOMPA LOOMPAS:                                             │
│   - Multiple components                                      │
│   - Async coordination                                       │
│   - State management (worktree pool)                        │
│   - Failure modes are subtle                                │
│                                                              │
│   Winner: Ralph (for simplicity)                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Speed

```
Task: Implement 10 independent features

RALPH (sequential):
- 10 features × 5 iterations × 2 min/iteration = 100 minutes

OOMPA LOOMPAS (parallel):
- 5 workers × 2 features each × 5 iterations × 2 min = 20 minutes
- Plus merge time: ~5 minutes
- Total: ~25 minutes

Winner: Oompa Loompas (4x faster)
```

### Quality

```
RALPH:
- Self-review only
- Catches ~60% of issues
- Rest require human review

OOMPA LOOMPAS:
- Separate reviewer agent
- Catches ~80% of issues
- Multiple perspectives

Winner: Oompa Loompas (higher automated quality)
```

### Resource Usage

```
RALPH:
- 1 agent running
- 1 set of files
- Minimal memory

OOMPA LOOMPAS:
- N agents running
- N worktrees (disk space)
- N × memory per agent

Winner: Ralph (lower resources)
```

### When to Use Which

**Use Ralph when:**
- Quick prototyping
- Simple, linear tasks
- Limited resources (single machine)
- You want to watch the agent work
- Learning how agents behave

**Use Oompa Loompas when:**
- Large codebases with many independent tasks
- Need for speed (parallelism)
- Quality matters (review process)
- Production deployments
- Team settings (multiple people starting tasks)

### The Hybrid Approach

You don't have to choose one forever:

```bash
# Start with Ralph for planning
for i in {1..5}; do
    claude -p < "Break this spec into 10 independent tasks"
done

# Switch to Oompa Loompas for execution
./oompa_loompas.py --tasks tasks.json --workers 5

# Back to Ralph for integration testing
for i in {1..10}; do
    claude -p < "Run integration tests and fix any issues"
done
```

Ralph for single-threaded work. Oompa Loompas for parallel work. Use the right tool for the job.

---

## Conclusion: The Coordination Insight

If there's one thing to take away from this exploration, it's this:

> **The difference between Ralph and Oompa Loompas isn't parallel execution. It's coordination.**

Anyone can run 10 Ralphs in parallel. The hard part is making them work together without stepping on each other's toes.

The coordination layer provides:
1. **Isolation**: Worktrees give each worker their own sandbox
2. **Review**: Separate agents catch each other's mistakes
3. **Synchronization**: Sequential merging prevents chaos
4. **Recovery**: Conflict resolution handles the unexpected

These aren't nice-to-haves. They're essential for parallel agent work.

### The Future

What comes next?

- **Smarter task distribution**: ML-based task assignment based on agent history
- **Dynamic worker scaling**: Add/remove workers based on queue depth
- **Cross-repo coordination**: Oompa Loompas across multiple repositories
- **Human-in-the-loop integration**: Pause for human review on high-risk changes

But those are topics for another exploration.

For now, we've built the foundation: a way to scale from one Ralph to many, without losing what made Ralph work in the first place.

---

## Appendix: Commands Reference

### Worktree Operations

```bash
# Create worktree
git worktree add <path> -b <branch> [start-point]

# List worktrees
git worktree list

# Remove worktree
git worktree remove <path>

# Prune stale worktree references
git worktree prune
```

### Useful Bash Snippets

```bash
# Create N worktrees
for i in $(seq 1 $N); do
    git worktree add "../wt-$i" -b "task-$i" main
done

# Clean up all worktrees
git worktree list --porcelain | grep "worktree" | cut -d' ' -f2 | xargs -I{} git worktree remove {}

# Run command in all worktrees
for wt in ../wt-*; do
    (cd "$wt" && echo "=== $wt ===" && pytest)
done
```

### Python Orchestration Skeleton

```python
import asyncio
import subprocess
from pathlib import Path

class SimpleOompaLoompas:
    def __init__(self, repo_path: str, num_workers: int):
        self.repo = Path(repo_path)
        self.num_workers = num_workers
        self.worktrees = []

    async def setup(self):
        for i in range(self.num_workers):
            wt_path = self.repo.parent / f"wt-{i}"
            subprocess.run([
                "git", "worktree", "add",
                str(wt_path), "-b", f"task-{i}", "main"
            ], cwd=self.repo)
            self.worktrees.append(wt_path)

    async def run_worker(self, wt_path: Path, task: str):
        for attempt in range(3):
            # Propose
            subprocess.run(["claude", "-p"],
                input=task.encode(), cwd=wt_path)

            # Review
            result = subprocess.run(
                ["claude", "-p"],
                input=b"Review the changes...",
                cwd=wt_path,
                capture_output=True
            )

            if b"APPROVED" in result.stdout:
                return True
        return False

    async def run(self, tasks: list[str]):
        await self.setup()

        workers = [
            self.run_worker(self.worktrees[i], tasks[i])
            for i in range(len(tasks))
        ]

        await asyncio.gather(*workers)
        await self.merge_all()

    async def merge_all(self):
        for wt in self.worktrees:
            branch = wt.name.replace("wt-", "task-")
            subprocess.run([
                "git", "checkout", "main"
            ], cwd=self.repo)
            subprocess.run([
                "git", "merge", "--no-ff", branch
            ], cwd=self.repo)

if __name__ == "__main__":
    oompa = SimpleOompaLoompas(".", 5)
    asyncio.run(oompa.run([
        "Implement user login",
        "Implement user logout",
        "Implement password reset",
        "Implement email verification",
        "Implement 2FA setup",
    ]))
```

---

*End of exploration. Time to build.*
