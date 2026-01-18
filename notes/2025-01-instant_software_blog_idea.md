# Blog Post Idea: Instant Software - Oompa Loompas Are Here to Stay

**Status:** Planning
**Written:** January 2025

---

## What Are Oompa Loompas?

**oompa_loompas.sh:**
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

**prompts/worker.md:**
```
Goal: Match spec.md
Process: Create/claim tasks in tasks/{pending,in_progress,complete}.md
Method: Isolate changes to your worktree, commit and merge when complete
```

That's it. 7 lines of bash. 3 lines of prompt. A swarm of parallel agents.

---

## Core Thesis

The future of AI coding isn't one super-agent working for hours. It's many small agents working in parallel for minutes.

"Instant software" - you describe what you want, and a swarm builds it while you watch.

## The Structure

```
repo/
├── spec.md                    # what to build
├── tasks/
│   ├── pending.md             # unclaimed work
│   ├── in_progress.md         # being worked on
│   └── complete.md            # done
├── prompts/
│   └── worker.md              # 3-line agent prompt
├── oompa_loompas.sh           # 7-line orchestrator
└── .w1-i1/, .w2-i1/, ...      # isolated worktrees (created at runtime)
```

## The Two Axes

```
        iter1    iter2    iter3    iter4    iter5
        ────────────────────────────────────────→ sequential
worker1:  ●───────●───────●───────●───────●
worker2:  ●───────●───────●───────●───────●      ↓ parallel
worker3:  ●───────●───────●───────●───────●
```

- **Workers:** parallel (independent agents)
- **Iterations:** sequential within each worker (sees own previous work)

## Why It Works

1. **Isolation:** Each iteration gets its own git worktree (real filesystem, can run tests)
2. **Coordination:** Shared tasks/ folder - agents claim work, avoid duplicates
3. **Convergence:** All agents aim at same spec.md

## Key Points to Cover

### Ralph → Oompa Loompas

Ralph loop = one agent, many iterations (sequential)
Oompa loompas = many agents, many iterations (parallel × sequential)

You don't make software faster by making one agent smarter. You make it faster by running more agents.

### The Three-Line Prompt

```
Goal: Match spec.md
Process: Create/claim tasks in tasks/{pending,in_progress,complete}.md
Method: Isolate changes to your worktree, commit and merge when complete
```

- **Goal:** What we're building
- **Process:** How we coordinate
- **Method:** How we isolate and integrate

### Why This Changes Everything

- **Speed:** 3 workers × 5 iterations = 15 iterations of work in the time of 5
- **Cost:** Parallel time ≈ sequential time (same API calls, faster wall-clock)
- **Quality:** More iterations = more self-correction

## Blog Structure

1. **Hook:** "What if software was instant?"
2. **What Are Oompa Loompas?** Show the 7-line script + 3-line prompt
3. **The Problem:** Long-running agents are fragile and slow
4. **The Insight:** Parallelize workers, serialize iterations
5. **The Coordination:** Task files, worktrees, merge pattern
6. **The Results:** What you can build in 10 minutes with 5 agents
7. **The Future:** Orchestration is the new programming

## Open Questions

- Do we show a real demo/video?
- How do we handle the "but merge conflicts!" objection?
- Do we need review step for minimal version or trust agents?

## Related Notes

- notes/2025-01-myth_of_long_running_tasks.md (the theory)
- notes/2025-01-oompa_loompa_exploration.md (deep dive)
- notes/2025-01-parallelism_model.md (the two axes)
