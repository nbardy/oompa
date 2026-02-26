# Swarm Design Guide: Building Effective Oompa Swarms

When asked to create or optimize an `oompa.json` swarm configuration, your primary goal is to design a resilient, efficient, and cost-effective multi-agent system. 

Do not just throw 5 identical large models at a problem. The best swarms rely on **asymmetric capabilities** and **explicit handoffs** via the filesystem task queue (`tasks/`).

This guide outlines the core philosophy and technical patterns for designing high-performance swarms.

---

## 1. The Core Philosophy: Asymmetric Roles

A successful swarm separates the *thinking* from the *typing*. If you ask a fast, cheap model to invent an architecture, it will fail. If you ask a slow, expensive model to fix 50 minor syntax errors, it will waste time and money.

You should compose your swarm using three distinct worker profiles:

### A. The Planner (The Coordinator)
*   **Model:** Large, high-reasoning (e.g., `claude:opus`, `codex:gpt-5.3-codex:xhigh`).
*   **Role:** The Planner does **not** write code. It reads the initial `spec.md`, explores the codebase to understand the architecture, and breaks the work down into atomic, highly detailed `.edn` files in `tasks/pending/`. 
*   **Traits:** `can_plan: true`, low iteration count (e.g., `iterations: 3`), single instance (`count: 1`).
*   **Prompting:** Use `config/prompts/planner.md`.

### B. The Advanced Executor (The Architect)
*   **Model:** Large/Medium, capable of handling ambiguity (e.g., `codex:gpt-5.3-codex:high`, `claude:sonnet`).
*   **Role:** Takes on complex, vague tasks that require structural changes, refactoring, or setting up new core abstractions. They can flesh out incomplete plans and handle the "hard" execution steps.
*   **Traits:** `can_plan: false`, medium iteration count.
*   **Prompting:** Use `config/prompts/worker.md` (which allows them to spawn their own sub-tasks if they realize a task is too big).

### C. The Simple Executor (The Typist)
*   **Model:** Small, fast, cheap (e.g., `opencode:opencode/kimi-k2.5-free`, `codex:gpt-5.3-codex:low`).
*   **Role:** Pure execution. They pick up highly detailed, densely scoped specs created by the Planner and implement them exactly as requested. They should not be theorizing, inventing, or making architectural decisions. They just expand the spec into code.
*   **Traits:** `can_plan: false`, high iteration count (e.g., `iterations: 15`), multiple instances (`count: 3-5`).
*   **Prompting:** Use `config/prompts/executor.md` (strictly forbids creating new tasks; focuses entirely on claiming and completing).

---

## 2. Configuration Patterns

When generating an `oompa.json`, combine these profiles based on the complexity of the user's request.

### Pattern: The "Heavy Lift" (New Feature / Refactor)
Best for taking a vague user spec and turning it into a massive PR.

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
*Why this works:* The single Codex `xhigh` planner spends its time writing perfect `.edn` specs. The two `high` workers tackle the tricky backend structural changes. The four free OpenCode workers churn through the resulting boilerplate, UI components, and unit tests simultaneously. The Claude `opus` reviewer acts as the final gatekeeper before merging.

### Pattern: The "Bug Swarm" (Distributed Fixing)
Best for when a test suite is failing and you need many hands to fix isolated issues rapidly.

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
*Why this works:* Planners aren't needed if the tasks are already defined (e.g., by a script that dumped failed tests into `tasks/pending/`). You just want maximum concurrency with fast models to try fixes, run tests in their isolated worktrees, and merge.

---

## 3. Creating Good Tasks (For Planners)

If you are acting as a Planner (or writing a custom prompt for one), you must create tasks that enable the "Simple Executors" to succeed. 

A bad task:
```edn
{:id "task-1" :description "Build the auth system"}
```
*Result:* The Simple Executor will panic, hallucinate an architecture, and fail the review.

A good task:
```edn
{:id "task-1"
 :summary "Implement JWT Auth Middleware"
 :description "Create src/auth.ts. It must export an Express middleware function verifyToken. It should read the Authorization header, strip the Bearer prefix, and verify using process.env.JWT_SECRET. If missing/invalid, return 401 JSON: {error: 'unauthorized'}."
 :files ["src/auth.ts", "tests/auth.test.ts"]
 :acceptance ["Middleware throws 401 on missing header", "Valid token adds req.user"]}
```
*Result:* The Simple Executor knows exactly what file to touch, what the logic is, and what tests to write. It executes perfectly in 1 iteration.

---

## 4. Best Practices for Swarm Construction

1.  **Always use `can_plan: false` for executors.** If you forget this, every worker will try to become a manager, leading to race conditions where 5 workers try to write conflicting task files simultaneously.
2.  **Use `config/prompts/executor.md` for simple models.** This bundled prompt specifically disables their ability to create tasks, forcing them to focus entirely on claiming existing `.edn` files.
3.  **Set appropriate `max_wait_for_tasks`.** If your Planner is using a very slow model, the executors might time out while waiting for the first task to appear. Set `"max_wait_for_tasks": 1200` (20 minutes) on executors if the Planner has a massive codebase to read.
4.  **Leverage custom prompt includes.** If the project has strict styling rules, create a `prompts/style_guide.md` and append it to the `prompt` array for ALL workers in the JSON config. Do not rely on them finding it themselves.
