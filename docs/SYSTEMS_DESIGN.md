# Systems Design Constitution

Oompa is a hybrid system: non-deterministic agents coordinated by deterministic software. This document defines the principles that govern the boundary between them.

Every design decision in this project should be traceable to one of these principles. If a decision can't be justified by a principle here, either the decision is wrong or a principle is missing.

## The Boundary Rule

> **Agents act. The framework reacts.**

Agents express intent through filesystem operations and text signals. The framework observes, validates, and finalizes. Agents never need to understand the framework. The framework never needs to understand the agent's reasoning.

## Principles

### 1. Agent flexibility is sacred

Agents are creative, unpredictable, and fallible. Don't constrain what they can do — constrain what *damage* they can do. An agent should be free to claim multiple tasks, create new tasks, split tasks, abandon tasks, write code across files, run tests, or change strategy mid-execution. The system must tolerate all of this without corrupting shared state.

The framework's job is to safely integrate agent output, not to micromanage agent process.

### 2. Deterministic software owns shared state

Every operation that touches shared state must be serialized or atomic. Agents interact with shared state through simple, atomic primitives (file moves, file reads). The framework handles everything that requires coordination: merging, completion tracking, recycling, annotation.

If two agents race for the same resource, the outcome must be deterministic and safe. One wins, the other gets a clear failure signal. No corruption, no lost work.

### 3. Behavior lives in prompts, not code

The orchestrator is a thin loop: setup, run agent, parse signals, react. All domain behavior — how to plan, how to execute, what tasks to create, when to stop — lives in prompt files.

Adding a new role means writing a new `.md` file, not adding a `case` branch. If you're writing framework code to handle a behavioral distinction between agents, you're doing it wrong — push it into the prompt.

### 4. Filesystem is the coordination protocol

No databases, no message queues, no RPC. State is files in directories. State transitions are file moves. Status is derived from which directory a file is in.

This makes the system inspectable (ls), debuggable (cat), and recoverable (mv). A human with a terminal can understand and fix any state the system gets into.

### 5. Branches are the review boundary

Anything an agent writes in its worktree is a proposal. It becomes real only when merged to main. This applies equally to code and to new tasks. Nothing an agent produces should enter shared state without passing through the merge/review gate.

### 6. Fail open for agents, fail safe for the system

Agent failures are expected and routine. The system must handle them without human intervention: recycle abandoned work, reset crashed sessions, retry with fresh state. No single agent failure should corrupt main, lose tasks, or block other agents.

The framework assumes agents will fail. Agents assume the framework will catch them.

### 7. The framework is the authority on task state

Agents can claim tasks (express intent). But the framework is the single source of truth for what is claimed, what is complete, and what needs recycling. If the agent's view and the framework's view disagree, the framework wins.

This eliminates dual-authority bugs: agent forgets to complete, agent completes before committing, agent completes the wrong task. The framework's observation is authoritative.

### 8. Prompts are contracts, not tutorials

The shared prompt surface (task header, signals) is an API. It should list capabilities and constraints, not teach strategy. Agents are smart enough to figure out workflow from a short list of what they can do.

Role-specific behavior (planning strategy, execution approach, task decomposition style) belongs in role-specific prompt files. Shared infrastructure should be as small and stable as a syscall table.

### 9. Prefer observable interfaces over invisible conventions

When agents interact with shared state, the mechanism should be visible and inspectable. Filesystem operations over in-memory state. Explicit signals over implicit detection. Scripts with output over silent side effects.

If something goes wrong, an operator should be able to reconstruct what happened from the artifacts on disk — without reading framework source code or agent logs.
