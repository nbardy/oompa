# Oompa Swarm Philosophy: Managing Information Flow

When designing a swarm, you are not writing code; you are designing an **information flow**. You are managing how ambiguity is processed into deterministic tasks.


## 1. Artificial Generalism

*"Do not handicap an oracle to simulate a human."*

Humans specialize because our brains have limited bandwidth and slow IO. We create strict job titles so we don't have to understand the whole system. LLMs are different. They read the entire codebase in seconds. Their primary advantage is cross-domain synthesis—the ability to realize that a CSS bug is actually caused by a database schema flaw.

An LLM does not need to forget how to code in order to be a good game designer. Over-constraining an agent with a hyper-specific persona ("You only write CSS", "You only do math") cripples their greatest strength: cross-domain intuition. If you prompt an agent with "You are a Junior CSS Developer, do not think about backend logic," you are actively destroying its highest-value capability.

A "Game Designer" agent should consider latency implications while designing a mechanic. An "Execution Engineer" should push back on a mechanic if it fundamentally breaks the UI flow. We should not write prompts that put blinders on the agents. Roles should be defined by the tasks they are allowed to pull from the queue, not by artificially capping their intelligence.

Every lead agent should essentially be prompted as a "Technical Founder" whose current focus just happens to be a specific domain. Direct their focus through the task they are assigned, not by artificially limiting their persona.

**In practice, this means tag-based routing over permission silos.** Stop trying to control agents by turning off their brains. Control them by tagging the work. Give them all access to the full context, but tell the "Executors" to only pick up `#eng-task` files, and the "Directors" to only pick up `#meta-task` files.


## 2. Divergence and Convergence

*"Creative thought requires sequence; execution requires scale."*

A swarm naturally wants to spread out and execute in parallel. This is incredibly destructive during the early phases of a project. If 10 agents try to "invent a game" simultaneously, you get 10 conflicting codebases.

The key insight is that every domain—game design, UI design, backend architecture—has the same two-phase structure. Both have iterative, design-based processes: proposing ideas, exploring ideas, critiquing ideas, and refining. And both have implementation modes where we can parallelize and fill in details—either details in code or details in design docs.

Design is inherently Waterfall. You cannot parallelize finding "the core loop of a game" or a core UI flow. Ideation, critique, and refinement must happen in a tight, sequential loop. You must force the swarm through a narrow bottleneck (a single planner, a specific folder state) until the core theory converges into an artifact (a Markdown spec).

Once the artifact exists, engineering can happen in parallel. You don't need a rigid Directed Acyclic Graph because LLMs can assume the shape of dependent work. Oompa Loompas are designed to operate as a flat, asynchronous field.

The bottleneck is the loop—execution is where scale wins. Only once ambiguity reaches zero can you unleash the swarm into convergent execution, where speed and scale take over.

**The queue shape implements this directly.** If the goal is Design (divergent), use the filesystem (Markdown files moving through folders) to force a sequential waterfall. If the goal is Execution (convergent), use the EDN task queue to enable flat, massively parallel work.


## 3. Stub and Smooth

*"A swarm flows around blockers; it does not wait for them."*

In traditional engineering, if the UI needs an API, the frontend engineer is blocked until the backend engineer finishes. In a swarm, blocking creates catastrophic traffic jams.

Because agents are generalists, they have the intuition to assume the shape of missing dependencies. The UI agent should assume the API response, build the component against that contract, and merge it. The backend agent will eventually see the UI's assumption and build the real API to match it.

Teach agents explicitly not to wait for dependencies. If an executor is building the UI and the API doesn't exist yet, the prompt must explicitly encourage them to write a mock/stub, merge it, and let the swarm smooth it out in a later iteration. Tolerate temporary breakage and mock interfaces. The swarm will eventually "smooth out" the inconsistencies over multiple iterations.

The same principle applies to knowledge. If a spec is incomplete, an agent should assume the shape of the missing paragraph, stub its understanding, build against that assumption, and let the Docs Architect (see §5) smooth it later. This is the secret to unlocking true flat parallelism.


## 4. The Intelligence-to-Ambiguity Ratio

*"Spend compute on uncertainty; optimize for speed on certainty."*

A successful swarm separates the *thinking* from the *typing*. If you ask a fast, cheap model to invent an architecture, it will fail. If you ask a slow, expensive model to fix 50 minor syntax errors, it will waste time and money. Intelligence should be deployed in direct proportion to the ambiguity of the task.

**High ambiguity — burn tokens.** Spending massive tokens on a slow, intelligent model (opus, codex:xhigh) upfront to produce a dense, mathematically sound plan is the best investment you can make. The cost of a bad architectural decision compounds exponentially across the swarm.

**Zero ambiguity — flood it.** Once the high-intelligence models have produced a dense, strict specification, the intelligence required to execute it drops near zero. We trade intelligence for speed and concurrency. We deploy 10 fast, cheap models (haiku, kimi) to slam out the code. If they fail, they fail fast.

**The Constant Reviewer.** The final gatekeeper must always be a high-tier model. They reflect, critique, and enforce the high standard on the cheap work produced by the swarm. Intelligence is a tool to eradicate ambiguity, not a requirement for every keystroke—but it must bookend the process.


## 5. Resisting Entropy

*"Without continuous maintenance, a swarm's shared reality fractures."*

If 10 agents run for an hour, the documentation diverges from the code. Shared reality drifts the moment a swarm starts moving, and it never stops.

You must dedicate a high-tier, low-iteration agent—a "Docs Architect" or "Chief Scientist"—whose sole purpose is to continuously read, consolidate, and clean the central `.md` specs so the executors don't get confused by stale reality. In any swarm that lasts longer than an hour, this role is not optional.
