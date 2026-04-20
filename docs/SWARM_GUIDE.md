# Swarm Design Guide: Building Effective Oompa Swarms

This guide translates the [Oompa Swarm Philosophy](./oompa_philosophy.md) into concrete `oompa.json` configurations. Read the philosophy first—this doc assumes you understand Artificial Generalism, Divergence/Convergence, Stub & Smooth, the Intelligence-to-Ambiguity Ratio, and Resisting Entropy.

For JSON worker configs, use `max_cycle`. The CLI `loop` command still uses `--iterations`.

Review is enabled by default whenever reviewers are configured. Set `"needs_review": false` on a worker to disable review for that worker, even if top-level reviewers are configured. Set `"needs_review": false` at the top level to disable only the generic reviewer block.


## 1. Worker Profiles

The Philosophy's Intelligence-to-Ambiguity Ratio maps directly to three worker profiles. Do not throw 5 identical large models at a problem.

### The Planner (High Ambiguity → Burn Tokens)

- **Model:** Large, high-reasoning (e.g., `claude:opus`, `codex:gpt-5.3-codex:xhigh`).
- **Role:** The Planner does **not** write code. It reads the initial `spec.md`, explores the codebase, and breaks the work down into atomic, highly detailed `.json` files in `tasks/pending/`. This is the Slow Squeeze—spending massive tokens upfront to produce a dense, mathematically sound plan. **Crucially, the Planner must format tickets according to the rules in [`JSON_TICKETS.md`](./JSON_TICKETS.md) so the swarm can parse and diff them cleanly.**
- **Config:** `can_plan: true`, low `max_cycle` (e.g., 3–5), single instance (`count: 1`).
- **Prompt:** `config/prompts/planner.md`

### The Advanced Executor (Medium Ambiguity → Capable Generalists)

- **Model:** Large/Medium (e.g., `codex:gpt-5.3-codex:high`, `claude:sonnet`).
- **Role:** Takes on complex tasks that require structural changes, refactoring, or setting up new abstractions. Per the Generalist Founder principle, these agents have full context access and can push back on plans that break other domains. They can spawn sub-tasks if they realize a task is too big.
- **Config:** `can_plan: false`, medium `max_cycle`.
- **Prompt:** `config/prompts/worker.md`

### The Simple Executor (Zero Ambiguity → Flood It)

- **Model:** Small, fast, cheap (e.g., `opencode:opencode/kimi-k2.5-free`, `codex:gpt-5.3-codex:low`).
- **Role:** Pure execution. They pick up densely scoped specs and expand them into code. The Simple Executor will panic, hallucinate an architecture, and fail the review if given vague tasks—so never give them vague tasks. These are the cheap, fast models you flood the problem with once ambiguity reaches zero.
- **Config:** `can_plan: false`, high `max_cycle` (e.g., 15), multiple instances (`count: 3–5`).
- **Prompt:** `config/prompts/executor.md` (strictly forbids creating new tasks)

### The Reviewer (The Constant Gatekeeper)

- **Model:** High-tier (e.g., `claude:opus`).
- **Role:** The final gatekeeper. They reflect, critique, and enforce the high standard on the cheap work produced by the swarm. Every swarm needs one. If the fast models fail, they fail fast, and the Reviewer catches it.
- **Prompt:** `config/prompts/reviewer.md`

### The Docs Architect (Resisting Entropy)

- **Model:** High-tier, low cycle count (e.g., `claude:sonnet`, `max_cycle: 2-3`).
- **Role:** In any swarm that lasts longer than an hour, documentation drifts. This agent's sole purpose is to continuously read, consolidate, and clean the central `.md` specs so the executors don't get confused by stale reality. Not optional for long-running swarms.
- **Config:** `can_plan: false`, low `max_cycle`, single instance. Runs on a loop or triggered periodically.
- **Prompt:** `config/prompts/docs_architect.md`


## 2. Swarm Patterns

### Pattern: The "Heavy Lift" (New Feature / Refactor)

For taking a vague user spec and turning it into a massive PR. This pattern implements the full Divergence → Convergence pipeline: one planner forces the sequential bottleneck, then executors fan out into flat parallel execution.

```json
{
  "workers": [
    {
      "model": "codex:gpt-5.3-codex:xhigh",
      "prompt": ["config/prompts/planner.md"],
      "max_cycle": 5,
      "count": 1
    },
    {
      "model": "codex:gpt-5.3-codex:high",
      "prompt": ["config/prompts/worker.md"],
      "max_cycle": 10,
      "count": 2,
      "can_plan": false
    },
    {
      "model": "opencode:opencode/kimi-k2.5-free",
      "prompt": ["config/prompts/executor.md"],
      "max_cycle": 15,
      "count": 4,
      "can_plan": false
    }
  ],
  "reviewer": {
    "model": "claude:opus",
    "prompt": ["config/prompts/reviewer.md"]
  }
}
```

The single `xhigh` planner spends its time writing perfect `.json` specs. The two `high` workers tackle tricky structural changes. The four free workers churn through boilerplate, UI components, and unit tests simultaneously. The `opus` reviewer acts as the final gatekeeper.

### Pattern: The "Bug Swarm" (Distributed Fixing)

For when a test suite is failing and you need many hands to fix isolated issues. Planners aren't needed if tasks are already defined (e.g., by a script that dumped failed tests into `tasks/pending/`). You just want maximum concurrency.

```json
{
  "workers": [
    {
      "model": "codex:gpt-5.3-codex:low",
      "prompt": ["config/prompts/executor.md", "config/prompts/fixer.md"],
      "max_cycle": 5,
      "count": 8,
      "can_plan": false
    }
  ]
}
```

### Pattern: The "Long Campaign" (Multi-Hour Swarm)

For large features or multi-day efforts where entropy becomes the dominant failure mode. Adds the Docs Architect to keep shared reality intact.

```json
{
  "workers": [
    {
      "model": "codex:gpt-5.3-codex:xhigh",
      "prompt": ["config/prompts/planner.md"],
      "max_cycle": 5,
      "count": 1
    },
    {
      "model": "claude:sonnet",
      "prompt": ["config/prompts/docs_architect.md"],
      "max_cycle": 3,
      "count": 1,
      "can_plan": false
    },
    {
      "model": "codex:gpt-5.3-codex:high",
      "prompt": ["config/prompts/worker.md"],
      "max_cycle": 10,
      "count": 2,
      "can_plan": false
    },
    {
      "model": "opencode:opencode/kimi-k2.5-free",
      "prompt": ["config/prompts/executor.md"],
      "max_cycle": 15,
      "count": 4,
      "can_plan": false
    }
  ],
  "reviewer": {
    "model": "claude:opus",
    "prompt": ["config/prompts/reviewer.md"]
  }
}
```


## 3. Creating Good Tasks

A bad task forces a Simple Executor to invent. A good task forces them to *type*.

**Bad:**
```json
{"id": "task-1", "description": "Build the auth system"}
```
The Simple Executor will panic, hallucinate an architecture, and fail the review.

**Good:**
```json
{
  "id": "task-1",
  "summary": "Implement JWT Auth Middleware",
  "description": "Create src/auth.ts. Export an Express middleware function verifyToken. Read the Authorization header, strip the Bearer prefix, verify using process.env.JWT_SECRET. If missing or invalid, return 401 JSON.",
  "files": ["src/auth.ts", "tests/auth.test.ts"],
  "acceptance": ["Middleware throws 401 on missing header", "Valid token adds req.user"]
}
```

The executor knows exactly what file to touch, what the logic is, and what tests to write. This is the Intelligence-to-Ambiguity Ratio in action: the Planner burned the tokens to eradicate ambiguity, so the executor can slam it out in one iteration.


## 4. Stub & Smooth in Practice

The Philosophy says: teach agents not to wait for dependencies. In the swarm config, this means **executor prompts must explicitly encourage stubbing.**

Your `executor.md` prompt should include guidance like:

> If a function, API endpoint, or module you depend on does not exist yet, do not stop. Write a stub or mock that matches your best assumption of the contract, mark it with a `// STUB:` comment, and continue. Another agent will replace the stub with the real implementation. Your job is to keep moving.

This also applies to specs. If a task references a design decision that hasn't been made yet, the executor should assume the most reasonable shape and note the assumption. The Docs Architect will catch and reconcile it.

Without this, every missing dependency becomes a traffic jam. With it, the swarm operates as a flat, asynchronous field where agents flow around blockers instead of queuing behind them.


## 5. Anti-Pattern: The Planning Stampede

The single most destructive swarm failure mode. Multiple workers with `can_plan: true` and a planning-oriented prompt will independently scan the codebase, identify the same gaps, and generate duplicate tasks — then execute them in parallel, producing conflicting implementations that stomp on each other.

**What it looks like:**
- 94 tasks self-generated when the planner only created 4
- The same minigame implemented 5 times by different workers
- Workers inventing work that nobody asked for
- 49 merges that advance only 3 features

**Why it happens:** `can_plan: true` + a prompt that says "identify what's missing and create tasks" = every worker becomes an independent planner. Soft coordination mechanisms (shared markdown files like `claimed_specs.md`) don't survive race conditions. By the time worker B checks the claim list, worker A has already created tasks and started executing.

**The rules:**

1. **One planner, one plan.** Use the dedicated `"planner"` config (runs once, synchronously, before workers start). Set `max_pending` high enough to cover the full scope. The planner burns tokens upfront to create the complete task batch — that's the Slow Squeeze.

2. **`can_plan: true` ≠ "be a planner."** Workers with `can_plan: true` should get the **executor** prompt, not the planner prompt. `can_plan` means "you can refill the queue if it empties" — scoped to breaking down a task you're already working on, not scanning the codebase for new work.

3. **Never give multiple workers the same planning prompt.** If two workers both have "identify unimplemented features and create tasks," they will identify the same features and create duplicate tasks. The planner is a singleton by design.

4. **If you need continuous planning, use one lead + many executors.** Set `count: 1` on the lead worker. It plans sequentially while executors consume in parallel. This is the Divergence → Convergence pipeline from the Philosophy.

**The safe config pattern for "implement everything":**

```json
{
  "planner": {
    "model": "large-model",
    "prompt": ["prompts/planner.md"],
    "max_pending": 60
  },
  "workers": [
    {
      "_role": "lead",
      "model": "large-model",
      "prompt": ["prompts/executor.md"],
      "max_cycle": 15,
      "count": 1,
      "can_plan": true
    },
    {
      "_role": "executor",
      "model": "fast-model",
      "prompt": ["prompts/executor.md"],
      "max_cycle": 30,
      "count": 8,
      "can_plan": false
    }
  ]
}
```

The planner creates the full batch. The single lead can create follow-up tasks if the queue empties. The 8 executors grind. Nobody duplicates work.


## 6. Anti-Pattern: The Stale Task Loop

Workers claim a task, investigate, find the work is already done (e.g. a bug was already fixed upstream), signal `COMPLETE_AND_READY_FOR_MERGE` — but there's no code diff. The framework recycles the task back to pending. The next worker picks it up. Repeat forever.

**Why it happens:** Tasks live at `../tasks/` outside the worktree, so `git diff` can't see task file moves. The framework checks `worktree-has-changes?` before allowing a merge, and "no changes" means "recycle."

**The fix (framework-level, shipped):** When a worker has claimed tasks and signals merge with no diff, the framework completes those tasks instead of recycling them. The worker verified the work — that's the deliverable.

**Prevention:** Use `COMPLETE_AND_READY_FOR_MERGE(reason)` to leave provenance on why a task was completed without a diff. The notes are written into the task's JSON metadata.


## 7. Plans in Git

The planner runs in the project root (not a worktree) and automatically commits task files after creating them (`git add tasks/pending/ && git commit`). This only works if `tasks/` is **not** gitignored.

**Un-ignore `tasks/` in your project's `.gitignore`.** This gives you:
- **Provenance:** `git log` shows exactly what was planned, when, and what the queue looked like
- **Cleanup audit:** The planner can delete stale/duplicate pending tasks and commit that cleanup
- **Post-mortem:** After a swarm, `git log -- tasks/` shows the full lifecycle of every task

Workers still interact with tasks via the framework (filesystem `mv` for claim/complete transitions). Worker worktrees can't see `../tasks/` in their git tree — that's by design. The framework owns runtime state; git owns the historical record.

**Planner cleanup duty:** The planner prompt should include a "Step 0" that audits and cleans the queue before creating new tasks. Compare `tasks/pending/` against `tasks/complete/` and `git log` to remove duplicates and stale tasks. This prevents the Planning Stampede from compounding across swarm runs.


## 8. Best Practices

1. **Always use `can_plan: false` for executors.** If you forget, every worker will try to become a manager, leading to race conditions where 5 workers try to write conflicting task files simultaneously.
2. **Use `config/prompts/executor.md` for simple models.** This prompt disables task creation and forces them to focus on claiming existing `.json` files.
3. **Set appropriate `max_wait_for_tasks`.** If your Planner uses a very slow model, executors might time out waiting for the first task. Set `"max_wait_for_tasks": 1200` (20 min) on executors for large codebases.
4. **Leverage custom prompt includes.** If the project has strict styling rules, create `prompts/style_guide.md` and append it to the `prompt` array for ALL workers. Don't rely on them finding it themselves.
5. **Tag-based routing over permission silos.** Give all agents full context access. Control what they work on by tagging tasks (`#eng-task`, `#meta-task`, `#design-task`), not by limiting their intelligence.
6. **Add a Docs Architect for any swarm over an hour.** Shared reality drifts the moment a swarm starts. Without a dedicated agent cleaning the specs, your executors will build against stale assumptions.
7. **Size `max_pending` to the full scope.** If you have 20 features to implement at 3 tasks each, set `max_pending: 60`. Starving the queue forces workers into planning mode, which causes the Planning Stampede.
8. **Post-swarm audit.** After a run, check `tasks/complete/` for duplicate titles. If the same feature appears 3+ times, your planner prompt is too open-ended or you have too many planners.
