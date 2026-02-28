# Swarm Design Guide: Building Effective Oompa Swarms

This guide translates the [Oompa Swarm Philosophy](./oompa_philosophy.md) into concrete `oompa.json` configurations. Read the philosophy first—this doc assumes you understand Artificial Generalism, Divergence/Convergence, Stub & Smooth, the Intelligence-to-Ambiguity Ratio, and Resisting Entropy.


## 1. Worker Profiles

The Philosophy's Intelligence-to-Ambiguity Ratio maps directly to three worker profiles. Do not throw 5 identical large models at a problem.

### The Planner (High Ambiguity → Burn Tokens)

- **Model:** Large, high-reasoning (e.g., `claude:opus`, `codex:gpt-5.3-codex:xhigh`).
- **Role:** The Planner does **not** write code. It reads the initial `spec.md`, explores the codebase, and breaks the work down into atomic, highly detailed `.edn` files in `tasks/pending/`. This is the Slow Squeeze—spending massive tokens upfront to produce a dense, mathematically sound plan. **Crucially, the Planner must format tickets according to the rules in [`EDN_TICKETS.md`](./EDN_TICKETS.md) to prevent complex escape characters (like LaTeX math) from crashing the Clojure parser.**
- **Config:** `can_plan: true`, low iterations (e.g., 3–5), single instance (`count: 1`).
- **Prompt:** `config/prompts/planner.md`

### The Advanced Executor (Medium Ambiguity → Capable Generalists)

- **Model:** Large/Medium (e.g., `codex:gpt-5.3-codex:high`, `claude:sonnet`).
- **Role:** Takes on complex tasks that require structural changes, refactoring, or setting up new abstractions. Per the Generalist Founder principle, these agents have full context access and can push back on plans that break other domains. They can spawn sub-tasks if they realize a task is too big.
- **Config:** `can_plan: false`, medium iterations.
- **Prompt:** `config/prompts/worker.md`

### The Simple Executor (Zero Ambiguity → Flood It)

- **Model:** Small, fast, cheap (e.g., `opencode:opencode/kimi-k2.5-free`, `codex:gpt-5.3-codex:low`).
- **Role:** Pure execution. They pick up densely scoped specs and expand them into code. The Simple Executor will panic, hallucinate an architecture, and fail the review if given vague tasks—so never give them vague tasks. These are the cheap, fast models you flood the problem with once ambiguity reaches zero.
- **Config:** `can_plan: false`, high iterations (e.g., 15), multiple instances (`count: 3–5`).
- **Prompt:** `config/prompts/executor.md` (strictly forbids creating new tasks)

### The Reviewer (The Constant Gatekeeper)

- **Model:** High-tier (e.g., `claude:opus`).
- **Role:** The final gatekeeper. They reflect, critique, and enforce the high standard on the cheap work produced by the swarm. Every swarm needs one. If the fast models fail, they fail fast, and the Reviewer catches it.
- **Prompt:** `config/prompts/reviewer.md`

### The Docs Architect (Resisting Entropy)

- **Model:** High-tier, low iteration (e.g., `claude:sonnet`, iterations: 2–3).
- **Role:** In any swarm that lasts longer than an hour, documentation drifts. This agent's sole purpose is to continuously read, consolidate, and clean the central `.md` specs so the executors don't get confused by stale reality. Not optional for long-running swarms.
- **Config:** `can_plan: false`, low iterations, single instance. Runs on a loop or triggered periodically.
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
      "iterations": 5,
      "count": 1
    },
    {
      "model": "codex:gpt-5.3-codex:high",
      "prompt": ["config/prompts/worker.md"],
      "iterations": 10,
      "count": 2,
      "can_plan": false
    },
    {
      "model": "opencode:opencode/kimi-k2.5-free",
      "prompt": ["config/prompts/executor.md"],
      "iterations": 15,
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

The single `xhigh` planner spends its time writing perfect `.edn` specs. The two `high` workers tackle tricky structural changes. The four free workers churn through boilerplate, UI components, and unit tests simultaneously. The `opus` reviewer acts as the final gatekeeper.

### Pattern: The "Bug Swarm" (Distributed Fixing)

For when a test suite is failing and you need many hands to fix isolated issues. Planners aren't needed if tasks are already defined (e.g., by a script that dumped failed tests into `tasks/pending/`). You just want maximum concurrency.

```json
{
  "workers": [
    {
      "model": "codex:gpt-5.3-codex:low",
      "prompt": ["config/prompts/executor.md", "config/prompts/fixer.md"],
      "iterations": 5,
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
      "iterations": 5,
      "count": 1
    },
    {
      "model": "claude:sonnet",
      "prompt": ["config/prompts/docs_architect.md"],
      "iterations": 3,
      "count": 1,
      "can_plan": false
    },
    {
      "model": "codex:gpt-5.3-codex:high",
      "prompt": ["config/prompts/worker.md"],
      "iterations": 10,
      "count": 2,
      "can_plan": false
    },
    {
      "model": "opencode:opencode/kimi-k2.5-free",
      "prompt": ["config/prompts/executor.md"],
      "iterations": 15,
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
```edn
{:id "task-1" :description "Build the auth system"}
```
The Simple Executor will panic, hallucinate an architecture, and fail the review.

**Good:**
```edn
{:id "task-1"
 :summary "Implement JWT Auth Middleware"
 :description "Create src/auth.ts. Export an Express middleware function verifyToken. Read the Authorization header, strip the Bearer prefix, verify using process.env.JWT_SECRET. If missing/invalid, return 401 JSON: {error: 'unauthorized'}."
 :files ["src/auth.ts", "tests/auth.test.ts"]
 :acceptance ["Middleware throws 401 on missing header" "Valid token adds req.user"]}
```

The executor knows exactly what file to touch, what the logic is, and what tests to write. This is the Intelligence-to-Ambiguity Ratio in action: the Planner burned the tokens to eradicate ambiguity, so the executor can slam it out in one iteration.


## 4. Stub & Smooth in Practice

The Philosophy says: teach agents not to wait for dependencies. In the swarm config, this means **executor prompts must explicitly encourage stubbing.**

Your `executor.md` prompt should include guidance like:

> If a function, API endpoint, or module you depend on does not exist yet, do not stop. Write a stub or mock that matches your best assumption of the contract, mark it with a `// STUB:` comment, and continue. Another agent will replace the stub with the real implementation. Your job is to keep moving.

This also applies to specs. If a task references a design decision that hasn't been made yet, the executor should assume the most reasonable shape and note the assumption. The Docs Architect will catch and reconcile it.

Without this, every missing dependency becomes a traffic jam. With it, the swarm operates as a flat, asynchronous field where agents flow around blockers instead of queuing behind them.


## 5. Best Practices

1. **Always use `can_plan: false` for executors.** If you forget, every worker will try to become a manager, leading to race conditions where 5 workers try to write conflicting task files simultaneously.
2. **Use `config/prompts/executor.md` for simple models.** This prompt disables task creation and forces them to focus on claiming existing `.edn` files.
3. **Set appropriate `max_wait_for_tasks`.** If your Planner uses a very slow model, executors might time out waiting for the first task. Set `"max_wait_for_tasks": 1200` (20 min) on executors for large codebases.
4. **Leverage custom prompt includes.** If the project has strict styling rules, create `prompts/style_guide.md` and append it to the `prompt` array for ALL workers. Don't rely on them finding it themselves.
5. **Tag-based routing over permission silos.** Give all agents full context access. Control what they work on by tagging tasks (`#eng-task`, `#meta-task`, `#design-task`), not by limiting their intelligence.
6. **Add a Docs Architect for any swarm over an hour.** Shared reality drifts the moment a swarm starts. Without a dedicated agent cleaning the specs, your executors will build against stale assumptions.
