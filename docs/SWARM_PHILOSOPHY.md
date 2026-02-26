# Oompa Swarm Philosophy: Managing Information Flow

When designing a swarm, you are not writing code; you are designing an **information flow**. You are managing how ambiguity is processed into deterministic tasks.

## 1. Artificial Generalism (No Handicapping)
Never handicap an oracle to simulate a human org chart. Do not write prompts like "You are a CSS developer, ignore backend logic." LLMs are generalists; their strength is cross-domain intuition.
* **Rule:** Control agents by the *tasks* they pull from the queue, not by artificially limiting their persona. Every lead agent should be prompted as a "Technical Founder" focused on a specific domain.

## 2. Divergence vs. Convergence (The Shape of Work)
Work modes are not strictly tied to "Engineering" vs "Design" domains; rather, they are tied to the *phase* of the work. Both Engineering and Design experience overlapping modes:

*   **Divergent Phases (Waterfall):** Ideation, critique, and core architecture must happen in sequence. You cannot parallelize finding the "fun loop" of a game, nor can you parallelize designing the core API data contracts of a backend. Force divergent work through a narrow bottleneck (a single planner, or a sequential pipeline of Markdown files).
*   **Convergent Phases (Flat):** Once the core theory or system architecture is spec'd, unleash the swarm. In Design, fleshing out character lore, color palettes, or sub-mechanics can happen in parallel. In Engineering, building the API routes against a shared spec happens in massive, flat parallelism via the EDN task queue (`tasks/pending/*.edn`).

## 3. The Contract-First Paradigm (Stub and Smooth)
Swarms flow around blockers; they do not wait for them. If the UI agent needs an API that doesn't exist yet, they should **never** block, and they should **never** build complex functional mocks.
* **Rule:** Teach executors to declare the missing shape and move on. Example: `// TODO(Backend_Agent): I need this endpoint returning this exact Interface`. This explicitly hands off the spec to the next agent. The swarm will "smooth out" the inconsistencies in subsequent iterations.

## 4. Intelligence-to-Ambiguity Ratio
Spend compute on uncertainty; optimize for speed on certainty.
* **High Ambiguity (Planners/Reviewers):** Burn tokens. Use the slowest, smartest models (`claude:opus`, `codex:xhigh`). The cost of a bad architectural decision compounds.
* **Zero Ambiguity (Executors):** Optimize for velocity. When executing a perfect `.edn` spec, high intelligence is wasted. Flood the problem with cheap, fast models (`claude:haiku`, `codex:low`, `opencode:kimi`).

## 5. Resisting Entropy (The Docs Architect)
In a swarm, shared reality fractures instantly. If 10 agents run for an hour, the documentation drifts.
* **Rule:** You must dedicate specific agents (e.g., a "Docs Architect" or "Chief Scientist") whose sole purpose is to continuously read, consolidate, and clean the central `.md` specs so the Executors don't get confused by stale rules.
