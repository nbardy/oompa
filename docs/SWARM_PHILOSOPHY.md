# Oompa Swarm Philosophy: Managing Information Flow

When designing a swarm, you are not writing code; you are designing an **information flow**. You are managing how ambiguity is processed into deterministic tasks.


## Part 1: The Meta-Principles of Swarm Engineering

### 1. The Principle of Artificial Generalism

*"Do not handicap an oracle to simulate a human."*

Humans specialize because our brains have limited bandwidth and slow IO. We create strict job titles ("CSS Developer") so we don't have to understand the whole system.

LLMs are different. They read the entire codebase in seconds. Their primary advantage is cross-domain synthesis—the ability to realize that a CSS bug is actually caused by a database schema flaw. If you prompt an agent with "You are a Junior CSS Developer, do not think about backend logic," you are actively destroying its highest-value capability. Over-constraining an agent with a hyper-specific persona ("You only write CSS", "You only do math") cripples their greatest strength: cross-domain intuition.

Treat all agents as "Technical Co-Founders." Direct their focus through the task they are assigned, not by artificially limiting their persona. We should not write prompts that put blinders on the agents. Instead, roles should be defined by the tasks they are allowed to pull from the queue, not by artificially capping their intelligence. Every lead agent should essentially be prompted as a "Technical Founder" whose current focus just happens to be a specific domain.

A "Game Designer" agent should consider latency implications while designing a mechanic. An "Execution Engineer" should push back on a mechanic if it fundamentally breaks the UI flow.


### 2. The Law of Divergence and Convergence

*"Creative thought requires sequence; execution requires scale."*

A swarm naturally wants to spread out and execute in parallel. This is incredibly destructive during the early phases of a project.

If 10 agents try to "invent a game" simultaneously, you get 10 conflicting codebases. Ideation, critique, and specification are inherently divergent and sequential (Waterfall). You must force the swarm through a narrow bottleneck (a single planner, a specific folder state) until the core theory is solidified. Only once ambiguity reaches zero can you unleash the swarm into convergent execution (Parallelism), where speed and scale take over.


### 3. The "Stub and Smooth" Paradigm

*"A swarm flows around blockers; it does not wait for them."*

In traditional engineering, if the UI needs an API, the frontend engineer is blocked until the backend engineer finishes. In a swarm, blocking creates catastrophic traffic jams.

Because agents are generalists (see Principle 1), they have the intuition to assume the shape of missing dependencies. The UI agent should assume the API response, build the component against that contract, and merge it. The backend agent will eventually see the UI's assumption and build the real API to match it.

Design swarms to be asynchronously flat. Tolerate temporary breakage and mock interfaces. The swarm will eventually "smooth out" the inconsistencies over multiple iterations.


### 4. The Intelligence-to-Ambiguity Ratio

*"Spend compute on uncertainty; optimize for speed on certainty."*

Intelligence (large models, slow reasoning tokens) should be deployed in direct proportion to the ambiguity of the task.

- **High Ambiguity** (System Design, Architecture): Burn tokens. Use the slowest, most capable models. The cost of a bad architectural decision compounds exponentially across the swarm.
- **Zero Ambiguity** (Typed specs, unit tests): Optimize for velocity. Once the high-intelligence models have produced a dense, strict specification, the intelligence required to execute it drops near zero. Flood the problem with cheap, fast models.

Intelligence is a tool to eradicate ambiguity, not a requirement for every keystroke.


### 5. Resisting Entropy

*"Without continuous maintenance, a swarm's shared reality fractures."*

In a swarm, shared reality drifts instantly. If 10 agents run for an hour, the documentation diverges from the code.

You must dedicate specific agents (e.g., a "Docs Architect" or "Chief Scientist") whose sole purpose is to continuously read, consolidate, and clean the central specs so the rest of the swarm does not get confused by stale reality.

---

## Part 2: The Overlap and Uniqueness of Design vs. Engineering

### The Shared Structure

UI design and game design both have iterative, design-based processes: proposing ideas, exploring ideas, critiquing ideas, and refining. And both have implementation modes where we can parallelize and fill in details—either details in code or details in design docs.

This is the overlap: both domains have a design loop and an implementation mode. The bottleneck is always the loop. Execution is where scale wins.


### The Waterfall of Design vs. The Flat Field of Execution

Oompa Loompas are designed to operate as a flat, asynchronous field (not a rigid Directed Acyclic Graph). They can hallucinate dependencies, build stubs, and integrate later. However, there is one exception: Design.

Design is inherently Waterfall: You cannot parallelize finding "the core loop of a game." Ideation, critique, and refinement must happen in a tight, sequential loop. Only after the core theory converges into an artifact (a Markdown spec) can the swarm fan out.

Engineering is Flat: Once the artifact exists, engineering can happen in parallel. You don't need a rigid DAG because LLMs can assume the shape of dependent work (e.g., frontend can mock the backend API until the backend agent finishes it).


### The Token/Speed Tradeoff (Cost is a Feature)

We do not shy away from burning tokens when it matters.

- **High-Cost Planning (The Slow Squeeze):** Spending massive tokens on a slow, intelligent model (opus, codex:xhigh) upfront to produce a dense, mathematically sound plan is the best investment you can make. It removes ambiguity.
- **Low-Cost Execution (The Fast Swarm):** When a plan has zero ambiguity, high intelligence is wasted. We trade intelligence for speed and concurrency. We deploy 10 fast, cheap models (haiku, kimi) to slam out the code. If they fail, they fail fast, and the high-level Reviewer catches it.
- **The Constant Reviewer:** The final gatekeeper must always be a high-tier model. They reflect, critique, and enforce the high standard on the cheap work produced by the swarm.

---

## Part 3: Concepts to Extract to the Skill Doc

**The Shape of the Queue Determines the Swarm:**
If the goal is Design (divergent), use the filesystem (Markdown files moving through folders) to force a sequential waterfall. If the goal is Execution (convergent), use the EDN task queue to enable flat, massively parallel work.

**Tag-Based Routing over Permission Silos:**
Stop trying to control agents by turning off their brains. Control them by tagging the work. Give them all access to the full context, but tell the "Executors" to only pick up #eng-task files, and the "Directors" to only pick up #meta-task files.

**The "Docs Architect" Pattern:**
In any swarm that lasts longer than an hour, documentation drifts. You must dedicate one high-tier, low-iteration agent to continuously read, consolidate, and clean the central .md specs so the Executors don't get confused by stale rules.

**The Stub & Smooth Principle:**
Teach agents explicitly not to wait for dependencies. If an executor is building the UI and the API doesn't exist yet, the prompt must explicitly encourage them to write a mock/stub, merge it, and let the swarm smooth it out in a later iteration. This is the secret to unlocking true flat parallelism.
