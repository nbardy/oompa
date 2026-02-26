# Oompa Swarm Philosophy: Managing Information Flow

When designing a swarm, you are not writing code; you are designing an **information flow**. You are managing how ambiguity is processed into deterministic tasks.

## The Meta-Principles of Swarm Engineering

### 1. The Principle of Artificial Generalism
"Do not handicap an oracle to simulate a human."
Humans specialize because our brains have limited bandwidth and slow IO. We create strict job titles ("CSS Developer") so we don't have to understand the whole system.
LLMs are different. They read the entire codebase in seconds. Their primary advantage is cross-domain synthesis—the ability to realize that a CSS bug is actually caused by a database schema flaw. If you prompt an agent with "You are a Junior CSS Developer, do not think about backend logic," you are actively destroying its highest-value capability.
* The Rule: Treat all agents as "Technical Co-Founders." Direct their focus through the task they are assigned, not by artificially limiting their persona.

### 2. The Law of Divergence and Convergence
"Creative thought requires sequence; execution requires scale."
A swarm naturally wants to spread out and execute in parallel. This is incredibly destructive during the early phases of a project.
If 10 agents try to "invent a game" simultaneously, you get 10 conflicting codebases. Ideation, critique, and specification are inherently divergent and sequential (Waterfall).
You must force the swarm through a narrow bottleneck (a single planner, a specific folder state) until the core theory is solidified.
Only once ambiguity reaches zero can you unleash the swarm into convergent execution (Parallelism), where speed and scale take over.

### 3. The "Stub and Smooth" Paradigm
"A swarm flows around blockers; it does not wait for them."
In traditional engineering, if the UI needs an API, the frontend engineer is blocked until the backend engineer finishes. In a swarm, blocking creates catastrophic traffic jams.
Because agents are generalists (see Principle 1), they have the intuition to assume the shape of missing dependencies. The UI agent should assume the API response, build the component against that contract, and merge it. The backend agent will eventually see the UI's assumption and build the real API to match it.
* The Rule: Design swarms to be asynchronously flat. Tolerate temporary breakage and mock interfaces. The swarm will eventually "smooth out" the inconsistencies over multiple iterations.

### 4. The Intelligence-to-Ambiguity Ratio
"Spend compute on uncertainty; optimize for speed on certainty."
Intelligence (large models, slow reasoning tokens) should be deployed in direct proportion to the ambiguity of the task.
* High Ambiguity (System Design, Architecture): Burn tokens. Use the slowest, most capable models. The cost of a bad architectural decision compounds exponentially across the swarm.
* Zero Ambiguity (Typed specs, unit tests): Optimize for velocity. Once the high-intelligence models have produced a dense, strict specification, the intelligence required to execute it drops near zero. Flood the problem with cheap, fast models.
* The Rule: Intelligence is a tool to eradicate ambiguity, not a requirement for every keystroke.

### 5. Resisting Entropy
"Without continuous maintenance, a swarm’s shared reality fractures."
In a swarm, shared reality drifts instantly. If 10 agents run for an hour, the documentation diverges from the code.
* The Rule: You must dedicate specific agents (e.g., a "Docs Architect" or "Chief Scientist") whose sole purpose is to continuously read, consolidate, and clean the central specs so the rest of the swarm does not get confused by stale reality.

---

## Technical Configuration Patterns

When writing \`oompa.json\`, apply the philosophy through these patterns:

1. **Gate Planning by Phase, Not by Count:**
   You can have many planners (\`can_plan: true\`), but they must be structured to prevent them from overwriting each other's work in chaos. Use tag-based routing so one planner handles `#backend-design` and another handles `#frontend-design`, or use a sequential file-based pipeline where planners hand off markdown files to each other.

2. **Restrict Execution-Only Models (\`can_plan: false\`):**
   If a model is deployed purely for velocity and volume, it MUST have \`can_plan: false\`. Their prompt must explicitly forbid creating new tasks. They only consume existing task files.

3. **Patience for Executors (\`max_wait_for_tasks\`):**
   If your Planners are slow models reading a massive codebase, the Executors will time out waiting for the first task. Set \`max_wait_for_tasks\` high (e.g., 1800 / 30 minutes) on executors to give the Planners time to work.

4. **The Code Standard Sandbox Prompting:**
   For simple executors, inject an exact code block in their prompt showing how to import, how to query the DB, and how to throw errors. Do not rely on cheap models reading the whole codebase to infer conventions.

## Tag-Based Task Routing

Use tags in the EDN task queue to cleanly coordinate parallel planning and execution without collisions.
* **Planners/Directors:** Output high-level epics tagged \`#meta-task\`.
* **Architects:** Consume \`#meta-task\` and break them down into type-safe, dense \`#eng-task\` files.
* **Executors:** ONLY claim tasks tagged \`#eng-task\`.
