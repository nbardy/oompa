# Oompa Loompas Parallelism Model

*Clarifying what's parallel, what's sequential, and why*

---

## The Key Insight

**Everything is parallel EXCEPT the moment of "rebase + merge"**

But that's not a queue - it's just: "Make sure you're up-to-date before you merge"

Git's natural ordering handles the rest.

---

## The Parallelism Model

```
═══════════════════════════════════════════════════════════════════════════════
                         PARALLEL EXECUTION MODEL
═══════════════════════════════════════════════════════════════════════════════

                              ┌─────────────────┐
                              │   TASK QUEUE    │
                              │  todos/ready/   │
                              └────────┬────────┘
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            ▼                          ▼                          ▼
   ┌─────────────────┐        ┌─────────────────┐        ┌─────────────────┐
   │    WORKER 0     │        │    WORKER 1     │        │    WORKER 2     │
   │   (Worktree)    │        │   (Worktree)    │        │   (Worktree)    │
   │                 │        │                 │        │                 │
   │ ┌─────────────┐ │        │ ┌─────────────┐ │        │ ┌─────────────┐ │
   │ │  PROPOSER   │ │        │ │  PROPOSER   │ │        │ │  PROPOSER   │ │
   │ │  (Claude)   │ │        │ │  (Claude)   │ │        │ │  (Claude)   │ │
   │ └──────┬──────┘ │        │ └──────┬──────┘ │        │ └──────┬──────┘ │
   │        │        │        │        │        │        │        │        │
   │        ▼        │        │        ▼        │        │        ▼        │
   │ ┌─────────────┐ │        │ ┌─────────────┐ │        │ ┌─────────────┐ │
   │ │  REVIEWER   │ │        │ │  REVIEWER   │ │        │ │  REVIEWER   │ │
   │ │  (Claude)   │ │        │ │  (Claude)   │ │        │ │  (Claude)   │ │
   │ └──────┬──────┘ │        │ └──────┬──────┘ │        │ └──────┬──────┘ │
   │        │        │        │        │        │        │        │        │
   │    ┌───┴───┐    │        │    ┌───┴───┐    │        │    ┌───┴───┐    │
   │    ▼       ▼    │        │    ▼       ▼    │        │    ▼       ▼    │
   │ [APPROVED] [FIX]│        │ [APPROVED] [FIX]│        │ [APPROVED] [FIX]│
   │    │       │    │        │    │       │    │        │    │       │    │
   │    │       └────┼──back──│    │       └────┼──back──│    │       └────│
   │    │       to   │   to   │    │       to   │   to   │    │       to   │
   │    │    proposer│proposer│    │    proposer│proposer│    │    proposer│
   │    ▼            │        │    ▼            │        │    ▼            │
   └────┼────────────┘        └────┼────────────┘        └────┼────────────┘
        │                          │                          │
        │                          │                          │
        │         ALL PARALLEL     │                          │
        │         ════════════     │                          │
        ▼                          ▼                          ▼
   ┌─────────────────────────────────────────────────────────────────────────┐
   │                         MERGE (Git handles sequencing)                   │
   │                                                                          │
   │   Each worker, when approved:                                           │
   │   1. git fetch origin main                                              │
   │   2. git rebase origin/main  ← Ensures up-to-date                       │
   │   3. Run tests (on rebased code)                                        │
   │   4. git push origin branch                                             │
   │   5. Merge to main (or PR)                                              │
   │                                                                          │
   │   If conflict during rebase:                                            │
   │   → Proposer resolves conflict                                          │
   │   → Re-run review if significant changes                                │
   │                                                                          │
   │   Git timestamps naturally sequence commits ← NO ARTIFICIAL QUEUE       │
   │                                                                          │
   └─────────────────────────────────────────────────────────────────────────┘
```

---

## What's Parallel vs Sequential

| Component | Parallel? | Notes |
|-----------|-----------|-------|
| Task pickup | ✅ Yes | Workers grab from queue independently |
| Proposer work | ✅ Yes | Each worker in own worktree |
| Reviewer work | ✅ Yes | Each reviewer reviews own worker's output |
| Fix iterations | ✅ Yes | Loop happens within each worker |
| Rebase before merge | ✅ Yes | Each worker does own rebase |
| Merge to main | ✅ Yes* | Git handles sequencing naturally |

*The only "sync" moment is: **rebase onto latest main before merge**.

That's not a queue - it's just good git hygiene.

---

## The Agent Roles

### PROPOSER
- Writes code based on task spec
- Runs tests in worktree
- Commits to feature branch
- Fixes code based on reviewer feedback

### REVIEWER
- Reviews the diff (separate agent = fresh eyes)
- Checks quality, correctness, tests
- Approves or rejects with specific feedback

### The Review Loop

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           REVIEW LOOP                                       │
│                                                                             │
│   PROPOSER                    REVIEWER                                      │
│   ════════                    ════════                                      │
│   Writes code                 Reviews diff                                  │
│   Runs tests                  Checks quality                                │
│   Commits to branch           Approves or rejects                          │
│                                                                             │
│                                                                             │
│   Attempt 1:  PROPOSER ──writes──→ code ──reviewed by──→ REVIEWER          │
│                                                              │              │
│                                                    ┌─────────┴─────────┐   │
│                                                    ▼                   ▼   │
│                                               [APPROVED]          [REJECTED]│
│                                                    │              + feedback│
│                                                    │                   │    │
│                                                    ▼                   │    │
│                                               [MERGE]                  │    │
│                                                                        │    │
│   Attempt 2:  PROPOSER ←──feedback──────────────────────────────────────    │
│               (same agent, fixes based on feedback)                         │
│                   │                                                         │
│                   └──writes fix──→ code ──reviewed by──→ REVIEWER          │
│                                                              │              │
│                                                         [APPROVED?]         │
│                                                                             │
│   Max 3 attempts, then escalate or skip                                    │
│                                                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Why NOT a Sequential Merge Queue?

### Wrong Mental Model ❌

```
Review Worker 0 → Merge → Review Worker 1 → Merge → Review Worker 2 → Merge
                  ↑
            "One at a time" - UNNECESSARILY SLOW
```

### Correct Mental Model ✅

```
Worker 0: [Propose] → [Review] → [Fix?] → [Review] ─────────┐
Worker 1: [Propose] → [Review] → [Fix?] → [Review] ─────────┼── ALL PARALLEL
Worker 2: [Propose] → [Review] → [Fix?] → [Review] ─────────┘
                                                            │
                                                            ▼
                                          ┌─────────────────────────────┐
                                          │  Before merge:              │
                                          │  1. git fetch + rebase main │
                                          │  2. Run tests               │
                                          │  3. git merge/push          │
                                          │  4. If conflict → resolve   │
                                          └─────────────────────────────┘
```

The key insight: **Multiple reviewers work in parallel**. Review responses happen in parallel. Git commits are naturally time-sequenced. The only requirement is that each proposer/reviewer pair is up-to-date with latest main BEFORE merging.

---

## The Simplified Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SIMPLIFIED OOMPA LOOMPAS                             │
│                                                                              │
│   SPEC FILES:                                                               │
│   ├── MASTER_DESIGN.md     (what to build - read only)                     │
│   └── ENGINEERING_PLAN.md  (how we're building - agent updates)            │
│                                                                              │
│   TASK QUEUE:                                                               │
│   └── todos/ready/*.md     (markdown, human-editable)                      │
│                                                                              │
│   SHARED MEMORY:                                                            │
│   └── agent_notes/         (learnings between iterations)                  │
│                                                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   WORKERS (all parallel):                                                   │
│                                                                              │
│   ┌───────────────┐  ┌───────────────┐  ┌───────────────┐                  │
│   │   Worker 0    │  │   Worker 1    │  │   Worker 2    │                  │
│   │               │  │               │  │               │                  │
│   │  Worktree 0   │  │  Worktree 1   │  │  Worktree 2   │  ← Isolation    │
│   │  Branch 0     │  │  Branch 1     │  │  Branch 2     │                  │
│   │               │  │               │  │               │                  │
│   │  Proposer     │  │  Proposer     │  │  Proposer     │  ← Parallel     │
│   │      ↓        │  │      ↓        │  │      ↓        │                  │
│   │  Reviewer     │  │  Reviewer     │  │  Reviewer     │  ← Parallel     │
│   │      ↓        │  │      ↓        │  │      ↓        │                  │
│   │  (fix loop)   │  │  (fix loop)   │  │  (fix loop)   │  ← Parallel     │
│   │               │  │               │  │               │                  │
│   └───────┬───────┘  └───────┬───────┘  └───────┬───────┘                  │
│           │                  │                  │                           │
│           ▼                  ▼                  ▼                           │
│                                                                              │
│   MERGE (each worker independently, git handles ordering):                  │
│                                                                              │
│   1. git fetch && git rebase main  ← Get latest                            │
│   2. Run tests                     ← Verify still works                    │
│   3. git push / merge to main      ← Git sequences by timestamp            │
│   4. If conflict → resolve → back to review if needed                      │
│                                                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   TERMINATION:                                                              │
│   - When todos/ready/ empty AND project meets spec                         │
│   - Output: DONE! <AGENT_TERMINATE>                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

---

## Self-Directed Task Creation

**Each worker can create new tasks as needed.** No special "task creator" agent required.

### How It Works

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SELF-DIRECTED TASK CREATION                          │
│                                                                              │
│   Worker 0 is implementing auth...                                          │
│                                                                              │
│   PROPOSER: "To complete login, I also need password hashing.               │
│              Creating new task: todos/ready/004-password-hashing.md"        │
│                                                                              │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  # Task: Implement Password Hashing                                  │   │
│   │                                                                      │   │
│   │  ## Context                                                          │   │
│   │  Discovered while implementing login (task 001).                     │   │
│   │  Login depends on this.                                              │   │
│   │                                                                      │   │
│   │  ## Requirements                                                     │   │
│   │  - Use bcrypt with cost factor 12                                    │   │
│   │  - Add hash_password() and verify_password() functions               │   │
│   │  - Add tests                                                         │   │
│   │                                                                      │   │
│   │  ## Files                                                            │   │
│   │  - src/auth/password.py                                              │   │
│   │  - tests/test_password.py                                            │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│   Worker 0 continues with original task...                                  │
│   Worker 1 or 2 can pick up the new task from the queue.                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why This Works

1. **Agent knows context** - While working, the agent discovers what's missing
2. **No bottleneck** - Don't need to wait for a "task manager" to create tasks
3. **Emergent parallelism** - Tasks spawn naturally, other workers pick them up
4. **Scope control** - Tasks must align with MASTER_DESIGN.md (no feature creep)

### Task Creation Rules

From Ralph's design:

```
✅ Agent CAN create tasks for:
   - Dependencies discovered during implementation
   - Tests for code being written
   - Refactors to unify code
   - Breaking large work into smaller chunks

❌ Agent CANNOT create tasks for:
   - Features beyond MASTER_DESIGN.md scope
   - "Nice to have" additions not in spec
   - Expanding requirements
```

### The Full Worker Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           WORKER DECISION FLOW                               │
│                                                                              │
│   START                                                                      │
│     │                                                                        │
│     ▼                                                                        │
│   ┌─────────────────────────────────────────┐                               │
│   │  Is there a task in todos/ready/?       │                               │
│   └─────────────────┬───────────────────────┘                               │
│                     │                                                        │
│           ┌─────────┴─────────┐                                             │
│           ▼                   ▼                                             │
│         [YES]               [NO]                                            │
│           │                   │                                             │
│           ▼                   ▼                                             │
│   ┌───────────────┐   ┌───────────────────────────────┐                    │
│   │ Pick task,    │   │ Check ENGINEERING_PLAN.md:    │                    │
│   │ move to       │   │ Is there more work needed?    │                    │
│   │ pending/      │   └───────────────┬───────────────┘                    │
│   └───────┬───────┘                   │                                     │
│           │                 ┌─────────┴─────────┐                           │
│           │                 ▼                   ▼                           │
│           │               [YES]               [NO]                          │
│           │                 │                   │                           │
│           │                 ▼                   ▼                           │
│           │         ┌─────────────┐     ┌─────────────┐                    │
│           │         │ CREATE new  │     │ DONE!       │                    │
│           │         │ tasks in    │     │ <TERMINATE> │                    │
│           │         │ todos/ready │     └─────────────┘                    │
│           │         └──────┬──────┘                                         │
│           │                │                                                │
│           └────────────────┴────────────────┐                               │
│                                             ▼                               │
│                                    ┌─────────────────┐                      │
│                                    │ PROPOSE → REVIEW│                      │
│                                    │ (fix loop)      │                      │
│                                    └────────┬────────┘                      │
│                                             │                               │
│                                             ▼                               │
│                                    ┌─────────────────┐                      │
│                                    │ MERGE           │                      │
│                                    │ (rebase first)  │                      │
│                                    └────────┬────────┘                      │
│                                             │                               │
│                                             ▼                               │
│                                    ┌─────────────────┐                      │
│                                    │ Move task to    │                      │
│                                    │ todos/complete/ │                      │
│                                    └────────┬────────┘                      │
│                                             │                               │
│                                             └──────────→ [LOOP BACK TO START]
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## No Special Agents Needed

The design has only **TWO agent roles**:

| Role | What It Does |
|------|--------------|
| **PROPOSER** | Writes code, creates tasks, fixes based on feedback |
| **REVIEWER** | Reviews diff, approves or rejects with feedback |

That's it. No separate:
- ❌ Task creator agent
- ❌ Task manager agent
- ❌ Merge coordinator agent
- ❌ Conflict resolver agent

The proposer handles task creation and conflict resolution.
The reviewer handles quality gates.
Git handles merge coordination.

---

## Summary

1. **Worktrees provide isolation** - Each worker has its own filesystem
2. **Proposer/Reviewer are separate agents** - Fresh eyes catch more bugs
3. **Everything runs in parallel** - No artificial queuing
4. **Git handles merge ordering** - Just rebase before merge
5. **Conflicts are resolved by proposer** - Re-review if significant changes
6. **Workers create their own tasks** - No task manager bottleneck

The design is simpler than it looks: **parallel workers with git-based coordination and self-directed task creation**.
