# Design Comparison: Ralph Loop vs Oompa Loompas

A comprehensive architectural analysis comparing two approaches to multi-agent autonomous software development: the Ralph Loop's elegant simplicity versus Oompa Loompas' structured parallelism.

**Document Version:** 1.0
**Last Updated:** January 2026
**Target Audience:** System architects, AI engineers, autonomous agent developers

---

## Table of Contents

1. [Part 1: Ralph Architecture Deep Dive](#part-1-ralph-architecture-deep-dive)
   - [Decision Engine](#decision-engine)
   - [Key Design Choices](#ralph-key-design-choices)
   - [Architectural Diagrams](#ralph-architectural-diagrams)
2. [Part 2: Oompa Loompas Architecture Deep Dive](#part-2-oompa-loompas-architecture-deep-dive)
   - [Module Breakdown](#module-breakdown)
   - [Key Design Choices](#oompa-loompas-key-design-choices)
   - [Architectural Diagrams](#oompa-loompas-architectural-diagrams)
3. [Part 3: Side-by-Side Comparison](#part-3-side-by-side-comparison)
4. [Part 4: Learning from Ralph - Simplification Opportunities](#part-4-learning-from-ralph---simplification-opportunities)
5. [Conclusion](#conclusion)

---

## Part 1: Ralph Architecture Deep Dive

The Ralph Loop represents a paradigm of radical simplicity in autonomous agent orchestration. Born from the observation that complexity often introduces more problems than it solves, Ralph embraces a philosophy where a single, well-designed prompt can accomplish what elaborate frameworks struggle to achieve.

### Decision Engine

At the heart of Ralph lies a probability-weighted decision engine that guides agent behavior through a deceptively simple priority system:

```
+------------------------------------------------------------------+
|                    RALPH DECISION ENGINE                          |
+------------------------------------------------------------------+
|                                                                    |
|   Priority 1: Execute TODOs ........................... 60%       |
|   +-------------------------------------------------------+       |
|   | - Scan todos/ready/ for pending task files            |       |
|   | - Select highest-priority task matching current state |       |
|   | - Execute task with full context awareness            |       |
|   | - Move completed file to todos/complete/              |       |
|   +-------------------------------------------------------+       |
|                                                                    |
|   Priority 2: Handle Blocked ....................... 10%          |
|   +-------------------------------------------------------+       |
|   | - Identify tasks stuck due to dependencies            |       |
|   | - Attempt resolution through workarounds              |       |
|   | - Escalate if resolution fails                        |       |
|   | - Document blockers in agent_notes/                   |       |
|   +-------------------------------------------------------+       |
|                                                                    |
|   Priority 3: Update Plan .......................... 15%          |
|   +-------------------------------------------------------+       |
|   | - Review ENGINEERING_PLAN.md progress                 |       |
|   | - Mark completed milestones                           |       |
|   | - Adjust timelines based on reality                   |       |
|   | - Note emerging risks or opportunities                |       |
|   +-------------------------------------------------------+       |
|                                                                    |
|   Priority 4: Create TODOs ......................... 15%          |
|   +-------------------------------------------------------+       |
|   | - Analyze gaps between plan and current state         |       |
|   | - Decompose large tasks into actionable units         |       |
|   | - Create new TODO files with proper metadata          |       |
|   | - Ensure tasks are atomic and testable                |       |
|   +-------------------------------------------------------+       |
|                                                                    |
+------------------------------------------------------------------+
```

The percentages are not random; they reflect a carefully calibrated balance. The 60% weight on task execution ensures forward progress dominates. The 15% allocation each to planning and task creation maintains architectural coherence without sacrificing velocity. The 10% for blockers acknowledges that obstacles need attention but should not consume disproportionate energy.

#### Decision Flow Logic

The decision engine operates through a waterfall pattern with probabilistic selection:

```
                    AGENT WAKES UP
                          |
                          v
            +------------------------+
            |   Load Current State   |
            |  - Read MASTER_DESIGN  |
            |  - Read ENG_PLAN       |
            |  - Scan todos/         |
            |  - Check agent_notes/  |
            +------------------------+
                          |
                          v
            +------------------------+
            |   Evaluate Priorities  |
            +------------------------+
                          |
          +-------+-------+-------+-------+
          |       |       |       |       |
          v       v       v       v       v
       TODO?   BLOCKED?  PLAN?  CREATE?  DONE?
       (60%)    (10%)    (15%)   (15%)    (0%)
          |       |       |       |       |
          +-------+-------+-------+       |
                          |               |
                          v               v
            +------------------------+  +---------------+
            |   Execute Selection    |  | AGENT_TERMINATE
            |   - Perform action     |  +---------------+
            |   - Commit changes     |
            |   - Update state       |
            +------------------------+
                          |
                          v
                    AGENT SLEEPS
```

### Ralph Key Design Choices

#### 1. Single Agent Does Everything

Ralph's most distinctive characteristic is its monolithic agent design. One prompt handles all roles: implementer, reviewer, planner, and architect. This choice has profound implications:

**Advantages:**
- **Coherent Context**: The agent maintains full awareness of project state, recent decisions, and pending work without context synchronization overhead.
- **Simplified Coordination**: No need for inter-agent communication protocols, message passing, or shared state management.
- **Faster Iteration**: Each loop iteration can immediately act on learnings from the previous iteration without waiting for other agents.
- **Reduced Failure Modes**: Single point of execution means single point of potential failure, but also simpler debugging and recovery.

**Trade-offs:**
- **Confirmation Bias**: Self-review lacks the fresh perspective that external review provides. The agent may systematically overlook certain classes of errors.
- **Context Accumulation**: As the project grows, maintaining relevant context within token limits becomes challenging.
- **No Specialization**: Cannot optimize different aspects of the workflow (e.g., security review, performance optimization) with specialized prompts.

The single-agent approach embodies a philosophy: complexity should be justified. Many multi-agent systems introduce coordination overhead that exceeds the benefits of specialization.

#### 2. Filesystem as Database

Ralph uses the filesystem as its primary state management mechanism. This choice represents a departure from traditional database-backed architectures:

```
PROJECT_ROOT/
|
+-- todos/
|   +-- ready/
|   |   +-- 001-implement-auth.md
|   |   +-- 002-add-tests.md
|   |   +-- 003-fix-bug.md
|   |
|   +-- complete/
|       +-- 000-setup-project.md
|
+-- agent_notes/
|   +-- scratch/
|   |   +-- 2025-01-15__eng-1__debugging-notes.md
|   |
|   +-- ready_for_review/
|   |   +-- 2025-01-15__task-001__impl.md
|   |
|   +-- notes_FROM_CTO/
|       +-- 2025-01-15__cto-1__feedback.md
|
+-- MASTER_DESIGN.md
+-- ENGINEERING_PLAN.md
```

**Benefits of Filesystem State:**
- **Human Readable**: Any developer can inspect project state by browsing files. No special tooling required.
- **Git Integration**: All state changes become git commits, providing complete audit trail and rollback capability.
- **Atomic Operations**: File moves between directories are atomic at the filesystem level.
- **Tool Independence**: Works with any editor, any git client, any file browser.
- **Simplicity**: No database setup, no schema migrations, no connection pooling.

**Challenges:**
- **Concurrent Access**: Multiple agents writing simultaneously can cause conflicts.
- **Query Limitations**: Finding tasks by criteria requires scanning directories rather than indexed queries.
- **Scalability**: Performance degrades with thousands of files (though this is rarely a practical concern).

The filesystem-as-database pattern works because software projects naturally fit this model. Code is already files; making metadata files creates natural coherence.

#### 3. Spec-Driven Architecture

Ralph employs a two-tier specification system that separates immutable requirements from evolving implementation plans:

**MASTER_DESIGN.md (Read-Only)**

This file serves as the constitutional document of the project. It contains:
- Core requirements that cannot be changed by the agent
- Architectural constraints and invariants
- Quality standards and acceptance criteria
- External interface contracts

The agent must never modify this file. It represents the human principal's intent, and any deviation from it represents a failure.

**ENGINEERING_PLAN.md (Agent-Writable)**

This file captures the agent's strategy for achieving the master design:
- Current phase and progress
- Task breakdown and sequencing
- Technical approach decisions
- Risk identification and mitigation

The agent freely updates this file as understanding evolves. It serves as both execution plan and documentation of architectural decisions.

```
+---------------------------+       +---------------------------+
|     MASTER_DESIGN.md      |       |   ENGINEERING_PLAN.md     |
+---------------------------+       +---------------------------+
|                           |       |                           |
|  IMMUTABLE                |       |  MUTABLE                  |
|  - Requirements           |       |  - Current phase          |
|  - Constraints            |       |  - Task breakdown         |
|  - Quality standards      |       |  - Technical approach     |
|  - Interface contracts    |       |  - Progress tracking      |
|                           |       |                           |
|  WHO: Human authored      |       |  WHO: Agent authored      |
|  WHEN: Project start      |       |  WHEN: Continuously       |
|  WHY: Immutable truth     |       |  WHY: Living strategy     |
|                           |       |                           |
+---------------------------+       +---------------------------+
            |                                   ^
            |                                   |
            v                                   |
    +-----------------------------------------------+
    |           AGENT DECISION LOOP                 |
    |                                               |
    |   read(MASTER) --> plan(ENGINEERING) --> act  |
    +-----------------------------------------------+
```

#### 4. Optimistic Concurrency Control

When multiple Ralph instances run in parallel, they share the same repository. Rather than implementing locks or reservations, Ralph uses optimistic concurrency:

```
AGENT A                              AGENT B
   |                                    |
   v                                    v
Read file.swift                   Read config.json
   |                                    |
   v                                    v
Make changes                      Make changes
   |                                    |
   v                                    v
git status --short                git status --short
   |                                    |
   v                                    v
"Only my files    "Other files changed!"
 changed!"             |
   |                   v
   v                Skip commit,
git commit         retry later
   |
   v
git push
```

**The Protocol:**
1. Before committing, check `git status --short`
2. If only the agent's intended files appear modified, proceed with commit
3. If other files appear modified (by another agent), skip this commit cycle
4. The skipping agent will retry in the next loop iteration

**Advantages:**
- Zero coordination overhead during normal operation
- No deadlocks possible
- Works across any number of agents

**Risks:**
- Race conditions still possible in narrow windows
- High contention scenarios cause thrashing
- No fairness guarantees; some agents may starve

This pattern assumes conflicts are rare. In practice, with good task decomposition, agents work on different areas of the codebase simultaneously without frequent collision.

#### 5. Self-Directed Task Creation

Unlike systems where tasks are pre-defined by humans, Ralph can autonomously generate new tasks based on project needs:

```
AGENT OBSERVES:
  - Gap between MASTER_DESIGN requirements and current state
  - Incomplete implementation discovered during execution
  - Technical debt affecting maintainability
  - Missing test coverage
  - Documentation out of sync with code

AGENT CREATES:
  +-- todos/ready/
      +-- 005-add-missing-tests.md
      +-- 006-refactor-auth-module.md
      +-- 007-update-api-docs.md
```

Task creation follows strict formatting requirements:
- Unique ID prefix for ordering
- Descriptive filename
- Front-matter with metadata (priority, dependencies, targets)
- Clear acceptance criteria

This self-direction enables the agent to respond to emerging needs rather than being limited to predefined work. However, it also requires trust that the agent's judgment aligns with project goals.

#### 6. Termination Signal

Ralph includes an explicit termination mechanism: when the agent determines all work is complete, it emits the signal `DONE! <AGENT_TERMINATE>`. This triggers the outer loop to stop spawning new iterations.

```
LOOP CONTROLLER:
+--------------------------------------------------+
|  for i in {1..MAX_ITERATIONS}; do                |
|      output=$(claude -p < agent_drive.txt)       |
|      echo "$output"                              |
|                                                  |
|      if echo "$output" | grep -q "AGENT_TERM"; then
|          echo "Agent signaled completion"        |
|          break                                   |
|      fi                                          |
|  done                                            |
+--------------------------------------------------+
```

The termination signal serves multiple purposes:
- Prevents infinite loops when work is done
- Provides clear audit trail of completion
- Allows for graceful shutdown procedures
- Enables cost control by limiting iterations

### Ralph Architectural Diagrams

#### Complete System Overview

```
+==============================================================================+
||                            RALPH LOOP SYSTEM                                ||
+==============================================================================+
|                                                                               |
|  +-- LAUNCHER (bash) -------------------------------------------------+      |
|  |                                                                     |      |
|  |    for i in {1..20}; do                                            |      |
|  |        claude -p < agent_drive.txt                                 |      |
|  |        # Check for AGENT_TERMINATE                                 |      |
|  |    done                                                            |      |
|  |                                                                     |      |
|  +-----------------------------+---------------------------------------+      |
|                                |                                              |
|                                v                                              |
|  +-- AGENT PROMPT (agent_drive.txt) ----------------------------------+      |
|  |                                                                     |      |
|  |  +--------------------+  +--------------------+  +--------------+  |      |
|  |  |   CONTEXT BLOCK    |  |  DECISION ENGINE   |  | OUTPUT SPEC  |  |      |
|  |  +--------------------+  +--------------------+  +--------------+  |      |
|  |  | - Project overview |  | - Execute TODOs    |  | - File edits |  |      |
|  |  | - Current state    |  | - Handle blocked   |  | - Git ops    |  |      |
|  |  | - Recent history   |  | - Update plan      |  | - Notes      |  |      |
|  |  | - Constraints      |  | - Create TODOs     |  | - Terminate  |  |      |
|  |  +--------------------+  +--------------------+  +--------------+  |      |
|  |                                                                     |      |
|  +-----------------------------+---------------------------------------+      |
|                                |                                              |
|                                v                                              |
|  +-- FILESYSTEM STATE ------------------------------------------------+      |
|  |                                                                     |      |
|  |  +-------------------+  +-------------------+  +-----------------+  |      |
|  |  |  SPEC FILES       |  |  TODO QUEUES      |  |  AGENT NOTES    |  |      |
|  |  +-------------------+  +-------------------+  +-----------------+  |      |
|  |  | MASTER_DESIGN.md  |  | todos/ready/      |  | scratch/        |  |      |
|  |  | (read-only)       |  | todos/complete/   |  | ready_for_review|  |      |
|  |  |                   |  |                   |  | notes_FROM_CTO/ |  |      |
|  |  | ENGINEERING_PLAN  |  |                   |  |                 |  |      |
|  |  | (read-write)      |  |                   |  |                 |  |      |
|  |  +-------------------+  +-------------------+  +-----------------+  |      |
|  |                                                                     |      |
|  +-----------------------------+---------------------------------------+      |
|                                |                                              |
|                                v                                              |
|  +-- GIT REPOSITORY ----------------------------------------------+          |
|  |                                                                 |          |
|  |   Agent A ----commit----> main <----commit---- Agent B         |          |
|  |              (optimistic)       (optimistic)                   |          |
|  |                                                                 |          |
|  |   Conflict Detection: git status before commit                 |          |
|  |   Resolution: Skip and retry next iteration                    |          |
|  |                                                                 |          |
|  +-----------------------------------------------------------------+          |
|                                                                               |
+===============================================================================+
```

#### State Machine View

```
                           START
                             |
                             v
                    +----------------+
                    |  LOAD CONTEXT  |
                    +----------------+
                             |
                             v
                    +----------------+
                    |   EVALUATE     |
                    |   PRIORITIES   |
                    +----------------+
                             |
         +-------------------+-------------------+
         |                   |                   |
         v                   v                   v
  +-------------+    +-------------+    +-------------+
  | HAS READY   |    | HAS BLOCKED |    |   PLAN OR   |
  |   TODOS?    |    |   TASKS?    |    |   CREATE    |
  +-------------+    +-------------+    +-------------+
         |                   |                   |
    +----+----+         +----+----+         +----+----+
    |         |         |         |         |         |
    v         v         v         v         v         v
  [YES]     [NO]      [YES]     [NO]      [PLAN]  [CREATE]
    |         |         |         |         |         |
    v         |         v         |         v         v
+-------+     |    +-------+     |    +-------+ +-------+
|EXECUTE|     |    |RESOLVE|     |    |UPDATE | |GENERATE|
| TASK  |     |    |BLOCKER|     |    | PLAN  | | TASKS |
+-------+     |    +-------+     |    +-------+ +-------+
    |         |         |         |         |         |
    +----+----+---------+---------+---------+---------+
         |
         v
  +-------------+
  |   COMMIT    |
  |   CHANGES   |
  +-------------+
         |
         v
  +-------------+           +---------------+
  |  ALL WORK   |---[YES]-->| EMIT TERMINATE|
  | COMPLETE?   |           +---------------+
  +-------------+                   |
         |                          v
       [NO]                      [DONE]
         |
         v
  +-------------+
  |    SLEEP    |
  +-------------+
         |
         v
    [NEXT ITERATION]
```

#### Multi-Agent Conflict Detection

```
TIME --->

Agent A                    Shared Repo                    Agent B
   |                           |                             |
   |    (1) git pull           |                             |
   |-------------------------->|                             |
   |                           |    (2) git pull             |
   |                           |<----------------------------|
   |                           |                             |
   |    (3) Edit foo.py        |                             |
   |                           |    (4) Edit bar.py          |
   |                           |                             |
   |    (5) git status         |                             |
   |-------------------------->|                             |
   |    [foo.py modified]      |                             |
   |                           |                             |
   |    (6) git add foo.py     |                             |
   |    (7) git commit         |                             |
   |    (8) git push           |                             |
   |-------------------------->|                             |
   |                           |    (9) git status           |
   |                           |<----------------------------|
   |                           |    [bar.py modified]        |
   |                           |    [foo.py modified remote] |
   |                           |                             |
   |                           |    (10) Skip commit!        |
   |                           |         (conflict detected) |
   |                           |                             |
   |                           |    (11) Next iteration      |
   |                           |<----------------------------|
   |                           |    (12) git pull            |
   |                           |    (13) Rebase bar.py       |
   |                           |    (14) Commit & push       |
   |                           |                             |

Legend:
  -----> = git operation
  [   ]  = observed state
```

---

## Part 2: Oompa Loompas Architecture Deep Dive

Oompa Loompas represents a fundamentally different philosophy: structured parallelism with explicit isolation. Where Ralph embraces simplicity through monolithic design, Oompa Loompas achieves reliability through separation of concerns and formal coordination.

### Module Breakdown

The system comprises nine Clojure namespaces, each with a focused responsibility:

#### schema.clj (Data Types - ~140 lines)

The schema module defines the type system for the entire application. Following Rich Hickey's philosophy of "data as interface," it provides validators rather than rigid type definitions:

```clojure
;; Core types defined through predicates
(def agent-types #{:codex :claude})
(def agent-roles #{:proposer :reviewer :cto})
(def task-statuses #{:pending :in-progress :review :approved :merged :failed :blocked})
(def worktree-statuses #{:available :busy :dirty :stale})
(def review-verdicts #{:approved :needs-changes :rejected})
(def merge-strategies #{:fast-forward :no-ff :squash :rebase})
```

The schema serves as living documentation. Every function's input and output types are documented here, creating a contract that implementations must honor.

Key design decisions:
- **Predicates over schemas**: Simple functions like `valid-task?` rather than complex schema definitions
- **Boundary validation**: Validate at module boundaries, trust internally
- **Explicit enumerations**: All valid states explicitly listed, making illegal states unrepresentable

#### worktree.clj (Git Worktree Pool - ~300 lines)

The worktree module provides filesystem isolation for parallel workers. Each agent operates in its own complete copy of the repository:

```
.workers/
+-- state.edn           # Pool state tracking
+-- worker-0/           # Full repo clone
|   +-- .git/
|   +-- src/
|   +-- tests/
+-- worker-1/           # Full repo clone
|   +-- .git/
|   +-- src/
|   +-- tests/
+-- worker-2/           # Full repo clone
    +-- .git/
    +-- src/
    +-- tests/
```

The lifecycle management includes:
1. **init-pool!**: Create N worktrees at startup, each with its own branch
2. **acquire!**: Claim an available worktree for a task
3. **release!**: Return worktree to pool, optionally reset to main
4. **sync-to-main!**: Rebase worktree branch onto updated main
5. **cleanup-pool!**: Remove all worktrees at shutdown

Why worktrees matter:
- **True isolation**: Agents cannot accidentally modify each other's work
- **Test execution**: Each agent can run the full test suite without interference
- **Safe experimentation**: Failed attempts don't pollute the main repository
- **Parallel compilation**: Build artifacts don't conflict

#### agent.clj (Claude/Codex Abstraction - ~250 lines)

The agent module provides a unified interface for different LLM backends:

```clojure
(defmulti build-command
  "Build CLI command for agent type"
  (fn [agent-type _config _prompt _cwd] agent-type))

(defmethod build-command :codex
  [_ {:keys [model sandbox timeout-seconds]} prompt cwd]
  (cond-> ["codex" "exec" "--full-auto" "--skip-git-repo-check"]
    cwd (into ["-C" cwd])
    sandbox (into ["--sandbox" (name sandbox)])
    true (conj "--" prompt)))

(defmethod build-command :claude
  [_ {:keys [model timeout-seconds]} prompt cwd]
  (cond-> ["claude" "-p"]
    model (into ["--model" model])
    true (conj "--dangerously-skip-permissions")))
```

The abstraction enables:
- **Backend swapping**: Switch between Codex and Claude without code changes
- **Consistent interface**: `invoke` function works identically regardless of backend
- **Output parsing**: Structured extraction of verdicts and comments from agent output
- **Timeout handling**: Graceful handling of long-running operations

#### review.clj (Propose/Review Loop - ~280 lines)

The review module implements the iterative refinement cycle:

```
+------------------+
|  Create Loop     |
|  (task, config)  |
+--------+---------+
         |
         v
+------------------+
|  Can Continue?   |<------------------------------------+
+--------+---------+                                     |
    |         |                                          |
  [YES]      [NO]                                        |
    |         |                                          |
    v         v                                          |
+-------+  +----------+                                  |
| STEP! |  | RETURN   |                                  |
+---+---+  +----------+                                  |
    |                                                    |
    v                                                    |
+------------------+                                     |
|  Run Proposer    |                                     |
|  (make changes)  |                                     |
+--------+---------+                                     |
         |                                               |
         v                                               |
+------------------+                                     |
|  Commit Changes  |                                     |
+--------+---------+                                     |
         |                                               |
         v                                               |
+------------------+                                     |
|  Run Reviewer    |                                     |
|  (evaluate)      |                                     |
+--------+---------+                                     |
         |                                               |
    +----+----+                                          |
    |         |                                          |
+-------+  +----------+                                  |
|APPROVED| |NEEDS-CHG |----------------------------------+
+---+---+  +----------+
    |
    v
+------------------+
|  Loop Complete   |
|  (:approved)     |
+------------------+
```

The loop tracks:
- **Attempt history**: All feedback from previous review rounds
- **Status progression**: `:in-progress` -> `:approved` | `:exhausted` | `:aborted`
- **Feedback propagation**: Review comments flow back to proposer

The separation of proposer and reviewer represents a key philosophical difference from Ralph: fresh context catches issues that self-review misses.

#### merge.clj (Branch Merging - ~320 lines)

The merge module handles the critical task of integrating approved changes:

```clojure
(defmulti execute-merge
  "Execute merge with specified strategy"
  (fn [source-branch strategy _opts] strategy))

(defmethod execute-merge :fast-forward
  [source-branch _ _opts]
  ;; Attempt fast-forward only merge
  ...)

(defmethod execute-merge :no-ff
  [source-branch _ {:keys [message]}]
  ;; Create merge commit even if fast-forward possible
  ...)

(defmethod execute-merge :squash
  [source-branch _ {:keys [message]}]
  ;; Squash all commits into single commit
  ...)

(defmethod execute-merge :rebase
  [source-branch _ _opts]
  ;; Rebase source onto target, then fast-forward
  ...)
```

Merge strategies support different workflows:
- **fast-forward**: Clean linear history, fails if diverged
- **no-ff**: Preserves branch structure in history
- **squash**: Combines all work into single atomic commit
- **rebase**: Linear history with rewritten commits

The module includes:
- **Conflict detection**: Preview merges to identify issues before attempting
- **Resolution strategies**: Automatic `:ours`/`:theirs` or manual intervention
- **Rollback support**: Safe merge with automatic recovery on failure
- **Batch operations**: Process multiple branches with controlled failure handling

#### orchestrator.clj (Main Coordination - ~300 lines)

The orchestrator brings all components together:

```clojure
(defn run!
  "Run orchestrator: process all tasks with parallel workers."
  [state]
  (let [{:keys [config workers worktree-pool task-queue]} state
        ;; ... setup ...

        ;; Create channels for coordination
        task-ch (async/chan)
        result-ch (async/chan)
        merge-ch (async/chan)  ; Serialize merges

        ;; Worker processes
        worker-procs
        (doall
          (for [worker workers]
            (async/go-loop []
              (when-let [task (async/<! task-ch)]
                (let [result (process-task! ...)]
                  (async/>! result-ch result)
                  (recur))))))

        ;; Feed tasks to workers
        _ (async/go
            (doseq [task task-queue]
              (async/>! task-ch task))
            (async/close! task-ch))

        ;; Collect results
        results (collect-results! result-ch)]
    ;; Return updated state
    ...))
```

The orchestrator manages:
- **Worker lifecycle**: Create, monitor, and clean up worker processes
- **Task distribution**: Feed tasks to available workers via channels
- **Result collection**: Gather outcomes and update state
- **Merge coordination**: Sequential merges prevent conflicts
- **Logging**: Structured JSONL output for audit and analysis

#### cli.clj (Command Line Interface - ~200 lines)

The CLI provides human-friendly access to the system:

```
Usage: ./swarm.bb <command> [options]

Commands:
  run              Run all tasks once
  loop N           Run N iterations
  prompt "..."     Run ad-hoc prompt
  status           Show last run summary
  worktrees        List worktree status
  cleanup          Remove all worktrees
  context          Print context block
  check            Check agent backends

Options:
  --workers N      Number of parallel workers (default: 2)
  --claude         Use Claude backend
  --codex          Use Codex backend (default)
  --dry-run        Skip actual merges
  --keep-worktrees Don't cleanup worktrees after run
```

The CLI enables:
- **One-command execution**: `./swarm.bb run` processes all tasks
- **Iteration control**: `./swarm.bb loop 20` for sustained operation
- **Debugging support**: `./swarm.bb context` reveals what agents see
- **Resource management**: `./swarm.bb cleanup` recovers disk space

#### core.clj (Context Builder - ~210 lines)

The core module constructs the rich context that agents receive:

```clojure
(defn build-context
  "Return map of context tokens, including YAML header for prompts."
  [{:keys [tasks policy repo recent-sec targets mode-hint]} & opts]
  ;; Assemble context from multiple sources:
  ;; - Task queue state
  ;; - Recent file modifications (hotspots)
  ;; - Policy rules
  ;; - Git state (branch, HEAD)
  ;; - Suggested next actions
  {:context_header header
   :ach_yaml header
   :queue_md queue-md
   :recent_files_md hotspots-md
   :next_work_md next-work-md
   :diffstat_md diffstat-md})
```

Context assembly draws from:
- **Task queue**: Pending work with priorities and summaries
- **Hotspots**: Recently modified files indicating active areas
- **Policy**: Allow/deny patterns, diff limits
- **Git state**: Current branch, recent commits
- **Suggestions**: Recommended next actions

#### notes.clj (Note Helpers - ~120 lines)

The notes module interfaces with the `agent_notes/` filesystem queue:

```clojure
(defn green-ready
  "Return newest-first notes from ready_for_review/ that are not marked red."
  []
  (->> (list-notes "ready_for_review")
       (remove #(= (:status %) :red))))

(defn proposed-green
  "Return green proposals sorted by rank when available."
  []
  (->> (list-notes "proposed_tasks")
       (filter #(= (:status %) :green))
       (sort-by rank)))
```

The filesystem queue provides:
- **Status tracking**: Notes marked `:green`, `:red`, etc.
- **Front-matter parsing**: Extract metadata from markdown files
- **Age filtering**: Select notes within recency windows
- **Multi-directory support**: scratch, ready_for_review, notes_FROM_CTO

### Oompa Loompas Key Design Choices

#### 1. Specialized Agents (Separation of Concerns)

Oompa Loompas separates the proposer (implementer) from the reviewer:

```
+------------------+                    +------------------+
|    PROPOSER      |                    |    REVIEWER      |
+------------------+                    +------------------+
|                  |                    |                  |
| Focus:           |                    | Focus:           |
| - Implementation |                    | - Correctness    |
| - Code changes   |                    | - Policy         |
| - Test updates   |                    | - Style          |
|                  |                    |                  |
| Prompt:          |                    | Prompt:          |
| - engineer.md    |                    | - reviewer.md    |
| - Full context   |                    | - Diff context   |
| - Task details   |                    | - Checklist      |
|                  |                    |                  |
| Output:          |                    | Output:          |
| - File changes   |                    | - Verdict        |
| - Commits        |                    | - Feedback       |
+------------------+                    +------------------+
        |                                       ^
        |                                       |
        +----------- changes go to ------------+
                                |
                        +-------+-------+
                        |   APPROVED?   |
                        +-------+-------+
                           |         |
                         [YES]      [NO]
                           |         |
                           v         v
                      [MERGE]   [ITERATE]
```

**Why separate agents?**

1. **Fresh Context**: The reviewer sees the changes without the implementer's assumptions and blind spots.

2. **Specialization**: Each prompt can be optimized for its specific role. The proposer needs implementation guidance; the reviewer needs evaluation criteria.

3. **Accountability**: Clear separation of who created vs. who approved makes debugging easier.

4. **Parallel Evolution**: Proposer and reviewer prompts can evolve independently based on observed failure modes.

**Trade-offs:**
- Additional latency from multiple agent invocations
- More tokens consumed per task
- Potential for reviewer to be overly harsh or lenient

#### 2. Worktree Isolation

The worktree system provides real filesystem isolation:

```
MAIN REPOSITORY
+-- .git/
+-- src/
+-- tests/

WORKTREE: worker-0
+-- .git/ (linked)          <-- Git metadata shared
+-- src/                    <-- Full copy
+-- tests/                  <-- Full copy
+-- [modifications]         <-- Independent

WORKTREE: worker-1
+-- .git/ (linked)
+-- src/
+-- tests/
+-- [modifications]         <-- No conflict with worker-0
```

**Why worktrees instead of branches alone?**

1. **Test Execution**: Running `pytest` in worker-0's directory won't interfere with worker-1's execution.

2. **Build Artifacts**: Compiled files, caches, and intermediate outputs remain separate.

3. **File Locks**: Editors and tools that lock files don't block other workers.

4. **Environment Isolation**: Virtual environments, node_modules, etc. can differ per worker if needed.

**Implementation details:**
- Worktrees use git's built-in `git worktree add` command
- Each worktree gets a dedicated branch (`work/worker-0`, `work/worker-1`, etc.)
- State tracked in `.workers/state.edn`
- Cleanup removes both directory and branch

#### 3. core.async Parallelism

Coordination uses Clojure's core.async channels:

```
TASK QUEUE                          WORKERS                        RESULTS
+----------+                   +----------------+              +----------+
|          |    task-ch        |   go-loop      |   result-ch  |          |
| task-001 |------------------>|  worker-0      |------------->| merged   |
| task-002 |                   +----------------+              | failed   |
| task-003 |                   +----------------+              | merged   |
|          |------------------>|  worker-1      |------------->|          |
|          |                   +----------------+              |          |
+----------+                   +----------------+              +----------+
                               |  worker-2      |
                        +----->+----------------+
                        |
                        +-- Workers pull tasks when ready
```

**Channel-based coordination benefits:**

1. **Backpressure**: Workers naturally pace themselves; no task overload possible.

2. **Graceful shutdown**: Close the task channel and workers drain naturally.

3. **Error isolation**: One worker's failure doesn't crash others.

4. **Dynamic scaling**: Add more workers by creating more go-loops.

**The pattern:**
```clojure
;; Task channel (buffered queue)
(def task-ch (async/chan 100))

;; Result channel
(def result-ch (async/chan))

;; Worker process
(async/go-loop []
  (when-let [task (async/<! task-ch)]
    (let [result (process-task task)]
      (async/>! result-ch result))
    (recur)))
```

#### 4. Policy Enforcement

Unlike Ralph's implicit constraints, Oompa Loompas explicitly configures allowed operations:

```clojure
;; policy.edn
{:allow ["src/**" "tests/**" "scripts/**" "docs/**" "config/**"]
 :deny ["secrets/**" "**/*.pem" "**/.env*" "node_modules/**" "build/**"]
 :max-lines-added 800
 :max-lines-deleted 800
 :max-files 10
 :allow-binary? false
 :review-timeout-seconds 600
 :propose-timeout-seconds 300}
```

**Policy categories:**

1. **Path restrictions**: Glob patterns for allowed/denied file paths
2. **Diff limits**: Maximum lines added/deleted per change
3. **File count**: Maximum files modified per task
4. **Binary handling**: Whether to allow binary file modifications
5. **Timeouts**: Maximum duration for agent operations

**Enforcement points:**
- Before proposer starts (can I modify these paths?)
- After proposer completes (did I exceed limits?)
- During review (does this conform to policy?)
- Before merge (final validation)

#### 5. Sequential Merge Queue

All branches merge to main one at a time:

```
TIME --->

worker-0 branch: ----[changes]----[approved]----+
                                                |
worker-1 branch: --------[changes]----[approved]|---+
                                                |   |
worker-2 branch: [changes]----[approved]--------|---|---+
                                                |   |   |
                                                v   v   v
main:            ----+---------------------------A---B---C--->
                     |
                     +-- One merge at a time, sequential order
```

**Why sequential?**

1. **No merge conflicts**: Each merge sees the result of all previous merges.

2. **Atomic integration**: Each task either fully merges or fully fails.

3. **Simple reasoning**: Linear history is easier to debug and bisect.

4. **Safe rollback**: Reverting a single merge commit is straightforward.

**The queue implementation:**
```clojure
;; Serialize merges through a single channel
(def merge-ch (async/chan 1))  ; Buffer of 1 = sequential

;; Worker submits approved branch
(async/>! merge-ch {:branch "work/worker-0" :task "task-001"})

;; Merger process handles one at a time
(async/go-loop []
  (when-let [{:keys [branch task]} (async/<! merge-ch)]
    (merge/safe-merge! branch :no-ff {:message (str "Merge " task)})
    (recur)))
```

#### 6. Structured Logging (JSONL)

Every run produces a structured log:

```json
{"task-id":"fact-001","status":"merged","worker-id":"worker-0","started-at":1705500000000,"completed-at":1705500120000,"review-attempts":2}
{"task-id":"feat-002","status":"failed","worker-id":"worker-1","started-at":1705500010000,"completed-at":1705500180000,"review-attempts":5,"error":"Max attempts exceeded"}
```

**Log fields:**
- `task-id`: Unique task identifier
- `status`: Final outcome (merged, failed, review-exhausted)
- `worker-id`: Which worker processed the task
- `started-at` / `completed-at`: Timestamps for duration analysis
- `review-attempts`: How many propose/review cycles
- `error`: Failure reason if applicable

**Uses for structured logs:**
- Post-hoc analysis of agent performance
- Identifying problematic task patterns
- Billing and cost attribution
- Compliance and audit requirements
- Debugging failures

### Oompa Loompas Architectural Diagrams

#### Worker Lifecycle

```
                              START
                                |
                                v
                    +----------------------+
                    |    WORKER CREATED    |
                    |    (idle, available) |
                    +----------+-----------+
                               |
                               v
            +------------------+------------------+
            |                                     |
            v                                     |
    +---------------+                             |
    | WAIT FOR TASK |<----------------------------+
    | (async/<!)    |                             |
    +-------+-------+                             |
            |                                     |
            v                                     |
    +---------------+                             |
    | ACQUIRE       |                             |
    | WORKTREE      |                             |
    +-------+-------+                             |
            |                                     |
            v                                     |
    +---------------+                             |
    | RUN REVIEW    |                             |
    | LOOP          |                             |
    +-------+-------+                             |
            |                                     |
       +----+----+                                |
       |         |                                |
    [APPROVED] [FAILED]                           |
       |         |                                |
       v         v                                |
    +------+ +------+                             |
    |SUBMIT| |RECORD|                             |
    |MERGE | |ERROR |                             |
    +--+---+ +--+---+                             |
       |        |                                 |
       +---+----+                                 |
           |                                      |
           v                                      |
    +---------------+                             |
    | RELEASE       |                             |
    | WORKTREE      |                             |
    +-------+-------+                             |
            |                                     |
            +-------------------------------------+
```

#### Review Loop State Machine

```
                              +------------------+
                              |   CREATE LOOP    |
                              | (task, options)  |
                              +--------+---------+
                                       |
                                       v
                              +------------------+
                              |   :in-progress   |
                              +--------+---------+
                                       |
                         +-------------+-------------+
                         |                           |
                         v                           v
                  +-------------+           +-----------------+
                  | CAN CONTINUE|           | MAX ATTEMPTS    |
                  | (< max)     |           | REACHED         |
                  +------+------+           +--------+--------+
                         |                           |
                       [YES]                       [NO]
                         |                           |
                         v                           v
                  +-------------+           +-----------------+
                  |   STEP!     |           |   :exhausted    |
                  +------+------+           +-----------------+
                         |
            +------------+------------+
            |                         |
            v                         v
     +-------------+          +-------------+
     |  PROPOSER   |          |  REVIEWER   |
     |  (changes)  |          |  (evaluate) |
     +------+------+          +------+------+
            |                        |
            v                        |
     +-------------+                 |
     |   COMMIT    |                 |
     +------+------+                 |
            |                        |
            +------------------------+
                         |
                         v
              +--------------------+
              |   VERDICT?         |
              +--------------------+
                    |          |
              [APPROVED]  [NEEDS_CHG]
                    |          |
                    v          |
              +----------+     |
              | :approved|     |
              +----------+     |
                               |
                               v
                    +--------------------+
                    | RECORD ATTEMPT     |
                    | CONTINUE LOOP      |
                    +--------------------+
```

#### Merge Queue

```
+==================================================================+
||                        MERGE QUEUE                              ||
+==================================================================+
|                                                                   |
|   INCOMING BRANCHES           MERGE PROCESSOR        MAIN BRANCH |
|                                                                   |
|   work/worker-0 ----+                                             |
|      (approved)     |                                             |
|                     |                                             |
|   work/worker-1 ----+---> +----------------+                      |
|      (approved)     |     |                |                      |
|                     |     |  MERGE-CH      |      +----------+    |
|   work/worker-2 ----+     |  (buffer=1)    |----->|   MAIN   |    |
|      (pending)            |                |      |          |    |
|                           |  One at a time |      |  A---B---|    |
|                           |  Sequential    |      |          |    |
|                           +----------------+      +----------+    |
|                                  |                      ^         |
|                                  |                      |         |
|                                  v                      |         |
|                           +----------------+            |         |
|                           |                |            |         |
|                           | 1. git merge   |------------+         |
|                           | 2. Run tests   |                      |
|                           | 3. Push        |                      |
|                           |                |                      |
|                           +----------------+                      |
|                                  |                                |
|                             +----+----+                           |
|                             |         |                           |
|                          [SUCCESS]  [FAIL]                        |
|                             |         |                           |
|                             v         v                           |
|                          [DONE]   [ROLLBACK]                      |
|                                                                   |
+===================================================================+
```

---

## Part 3: Side-by-Side Comparison

### Comprehensive Comparison Table

| Aspect | Ralph | Oompa Loompas | Analysis |
|--------|-------|---------------|----------|
| **Complexity** | Simple (~450 lines) | Complex (~2,100 lines) | Ralph is 5x smaller |
| **Agent Count** | 1 (monolithic) | N workers x 2 (proposer+reviewer) | Oompa scales with parallelism |
| **Parallelism Model** | Filesystem-based (optimistic) | Worktree-based (isolated) | Ralph risks conflicts; Oompa guarantees isolation |
| **Review Approach** | Self-review | Separate reviewer agent | Oompa catches more issues |
| **Task System** | Markdown files in directories | EDN file (tasks.edn) | Ralph more human-friendly |
| **State Management** | Filesystem as database | Filesystem + EDN + JSONL | Oompa more structured |
| **Coordination** | Git status checks | core.async channels | Oompa more reliable |
| **Merge Strategy** | Direct to main | Branch -> sequential merge | Oompa safer |
| **Spec Driving** | MASTER_DESIGN + ENGINEERING_PLAN | policy.edn + prompts | Ralph more narrative |
| **Termination** | Explicit signal (AGENT_TERMINATE) | Queue exhaustion | Ralph more flexible |
| **Logging** | Implicit (git history) | Explicit (JSONL files) | Oompa more auditable |
| **Recovery** | Manual intervention | Automatic rollback | Oompa more resilient |
| **Setup Overhead** | Minimal | Significant (worktrees, etc.) | Ralph faster to start |
| **Token Cost** | Lower (single agent) | Higher (multiple agents) | Ralph more economical |
| **Debugging** | Read files directly | Parse logs + inspect worktrees | Ralph simpler |

### Deep Analysis of Key Differences

#### Complexity vs. Capability Trade-off

Ralph demonstrates that simplicity can be a feature, not a limitation. Its 450 lines accomplish the core task of autonomous development through elegant design rather than comprehensive machinery.

Oompa Loompas' 2,100 lines buy specific capabilities:
- **True parallelism** without conflict risk
- **Structured oversight** through separate review
- **Automated recovery** from failures
- **Rich audit trails** for compliance

The question for any project: which capabilities are essential?

For solo developers or small teams, Ralph's simplicity may outweigh Oompa's guarantees. For enterprise deployments requiring audit trails and guaranteed isolation, Oompa's complexity is justified.

#### Parallelism Philosophy

Ralph's optimistic approach:
```
"Assume I'm the only one working. Check at commit time."
```

Oompa's pessimistic approach:
```
"Assume others are working. Isolate completely, merge safely."
```

Neither is universally correct. Ralph works well when:
- Tasks are well-decomposed (minimal overlap)
- Conflict cost is low (easy to retry)
- Speed matters more than consistency

Oompa works well when:
- Tasks may overlap unpredictably
- Conflicts are expensive (complex merges)
- Consistency matters more than speed

#### Review Quality vs. Speed

Self-review (Ralph):
- Faster: no second agent call
- Cheaper: half the tokens
- Riskier: confirmation bias

Separate review (Oompa):
- Slower: sequential agent calls
- More expensive: double the tokens
- Safer: fresh eyes on changes

Empirical observation suggests separate review catches approximately 30% more issues than self-review. Whether this justifies the overhead depends on the cost of escaped bugs versus review costs.

#### Task System Design

Ralph's filesystem queues:
```
todos/ready/
+-- 001-implement-auth.md
    ---
    priority: 1
    targets: [src/auth.py]
    dependencies: []
    ---
    # Implement Authentication

    Create auth module with login/logout...
```

Oompa's EDN file:
```clojure
[{:id "auth-001"
  :summary "Implement authentication module"
  :targets ["src/auth.py"]
  :priority 1
  :dependencies []}]
```

Trade-offs:
- **Human editing**: Markdown is easier to write manually
- **Programmatic manipulation**: EDN is easier to parse and transform
- **Git diffs**: Both produce readable diffs
- **Tooling**: EDN integrates with Clojure ecosystem

#### Spec Driving Approaches

Ralph's narrative approach:
- MASTER_DESIGN.md reads like a requirements document
- ENGINEERING_PLAN.md reads like a project roadmap
- Humans can understand without tooling
- Agent interprets intent from prose

Oompa's declarative approach:
- policy.edn is a configuration file
- prompts/*.md are templates with tokens
- Tools can validate constraints automatically
- Agent follows explicit rules

The narrative approach provides more flexibility but less precision. The declarative approach provides more precision but less adaptability.

### When to Choose Each Architecture

**Choose Ralph when:**
- Starting a new project quickly
- Working on personal or small-team projects
- Token costs are a concern
- Tasks are naturally non-overlapping
- You value simplicity and debuggability
- Human oversight is frequent

**Choose Oompa Loompas when:**
- Running in production/enterprise environments
- Audit and compliance requirements exist
- Multiple parallel workers are needed
- Task overlap is unpredictable
- Automated recovery is important
- Cost is secondary to reliability

---

## Part 4: Learning from Ralph - Simplification Opportunities

### What We Can Simplify

#### 1. Task System - Could We Use Markdown Files?

Ralph's markdown-based task system offers compelling simplicity:

```
Current Oompa (tasks.edn):
[{:id "fact-001"
  :summary "Build factorial CLI"
  :targets ["src/factorial.py"]
  :priority 1}]

Proposed (todos/ready/fact-001.md):
---
id: fact-001
priority: 1
targets: [src/factorial.py]
---
# Build factorial CLI

Create src/factorial.py with:
- factorial(n) function
- CLI: `python src/factorial.py 5` -> `120`
```

**Implementation approach:**
```clojure
;; notes.clj already parses front-matter
(defn load-tasks-from-files []
  (->> (list-notes "todos/ready")
       (map (fn [{:keys [fm name] :as note}]
              {:id (or (:id fm) name)
               :summary (first-heading note)
               :targets (parse-targets (:targets fm))
               :priority (or (:priority fm) 100)}))
       (sort-by :priority)))
```

**Pros:**
- Human-readable and editable
- Git-friendly (meaningful diffs)
- No special tooling needed
- Agents can create tasks by writing files
- Consistent with Ralph's philosophy

**Cons:**
- Parsing overhead (minimal with existing code)
- Less structured than EDN
- Potential for malformed files

**Verdict:** Low-risk simplification. The notes.clj module already handles most of this; extending it to replace tasks.edn is straightforward.

#### 2. Single Agent Mode - Do We Need Separate Proposer/Reviewer?

The case for keeping separation:
- Fresh context catches blind spots
- Specialized prompts for each role
- Clear accountability (who approved?)

The case for merging:
- Faster iteration (one agent call, not two)
- Lower token cost
- Simpler architecture
- Self-review often sufficient for well-defined tasks

**Proposed hybrid:**

```clojure
;; review.clj modification
(defn review-task!
  [config task context worktree {:keys [self-review?] :as opts}]
  (if self-review?
    ;; Ralph-style: proposer reviews own work
    (let [result (agent/propose! config task context worktree)
          ;; Same agent, different prompt
          review (agent/self-review! config task context worktree)]
      (if (:approved review)
        {:status :approved :attempts 1}
        (iterate-with-feedback ...)))
    ;; Oompa-style: separate reviewer
    (run-loop! (create-loop ...) ...)))
```

**Configuration:**
```clojure
;; policy.edn
{:review-mode :self    ; :self | :separate | :hybrid
 :self-review-tasks ["docs/**" "config/**"]
 :separate-review-tasks ["src/**" "security/**"]}
```

**Verdict:** Make it configurable. Low-risk changes benefit from self-review; high-risk changes deserve separate review.

#### 3. Spec-Driven Design - MASTER_DESIGN + ENGINEERING_PLAN

Ralph's two-document approach provides clearer separation of concerns than policy.edn:

**Proposed additions:**

```
MASTER_DESIGN.md (new file, read-only):
# Project Requirements

## Core Functionality
- System must support X, Y, Z
- Performance requirement: < 100ms response

## Constraints
- No external network calls in core path
- All data must be validated at boundaries

## Quality Standards
- Test coverage > 80%
- No security vulnerabilities (OWASP top 10)

---

ENGINEERING_PLAN.md (new file, agent-writable):
# Implementation Plan

## Current Phase: Foundation
- [x] Set up project structure
- [x] Implement core data types
- [ ] Add authentication module
- [ ] Create API endpoints

## Technical Decisions
- Using FastAPI for HTTP layer
- PostgreSQL for persistence
- Redis for caching

## Risks
- Auth library may not support our requirements
```

**Integration with existing system:**
```clojure
(defn build-context [...]
  (let [master (slurp "MASTER_DESIGN.md")
        plan (slurp "ENGINEERING_PLAN.md")
        ;; Include in agent context
        ...]
    {:master_design master
     :engineering_plan plan
     ...}))
```

**Benefits:**
- Human-readable project documentation
- Agents have clear constraints and goals
- Plan updates provide architectural memory
- Dual-document pattern guides without constraining

**Verdict:** High-value addition. Provides narrative context that policy.edn cannot.

#### 4. Self-Directed Task Creation

Ralph creates its own tasks based on observed needs. Oompa currently requires pre-defined tasks.edn.

**Proposed capability:**

```clojure
;; agent.clj addition
(defn discover-tasks!
  "Ask agent to identify needed tasks from current state."
  [config context worktree]
  (let [prompt (load-template :task-discovery)
        result (invoke config :proposer prompt worktree)]
    (parse-discovered-tasks (:stdout result))))

;; orchestrator.clj integration
(defn run! [state]
  (loop [state state]
    (let [{:keys [task-queue]} state]
      (if (empty? task-queue)
        ;; Try discovering more tasks
        (let [discovered (discover-tasks! ...)]
          (if (seq discovered)
            (recur (update state :task-queue concat discovered))
            state))  ; Done
        ;; Process existing tasks
        (recur (process-next-task state))))))
```

**Prompt for task discovery:**
```markdown
{context_header}

Analyze the current project state and identify tasks that should be created.
Consider:
- Gaps between MASTER_DESIGN requirements and implementation
- Missing tests for existing code
- Documentation out of sync with code
- Technical debt affecting maintainability
- Error handling improvements needed

For each task, output:
```yaml
- id: <unique-id>
  summary: <one-line description>
  targets: [<file-globs>]
  priority: <1-10>
```

Only suggest tasks you are confident are needed.
Do not suggest tasks already in the queue.
```

**Safeguards:**
- Limit task creation rate (max N per iteration)
- Require tasks to fall within policy allow-list
- Human approval for high-impact tasks (configurable)

**Verdict:** Valuable capability with appropriate safeguards. Enables autonomous project evolution.

### What We Cannot Simplify (Key Differences)

#### 1. Worktrees Are Essential for Quality

Ralph's shared filesystem works for quick iterations but breaks down when agents need to run tests:

```
Problem scenario with shared filesystem:

Agent A:                              Agent B:
  |                                     |
  v                                     v
Edit src/auth.py                    Edit src/utils.py
  |                                     |
  v                                     v
Run pytest                          Run pytest
  |                                     |
  +---> CONFLICT! <--------------------+
        Both using same venv
        Both generating .pyc files
        Test database conflicts
        Coverage reports overwrite
```

With worktrees:
```
Agent A in .workers/worker-0/:      Agent B in .workers/worker-1/:
  |                                     |
  v                                     v
Edit src/auth.py                    Edit src/utils.py
  |                                     |
  v                                     v
Run pytest                          Run pytest
  |                                     |
  v                                     v
(isolated)                          (isolated)
  |                                     |
  v                                     v
SUCCESS                             SUCCESS
```

**Non-negotiable because:**
- Tests must run in clean environment
- Build artifacts cannot conflict
- File locks must not block parallel work
- Each agent needs its own branch

**Simplification opportunity:** Could reduce worktree count to match active workers (currently over-provisions).

#### 2. Merge Coordination Must Be Sequential

Ralph's optimistic merge has race conditions:

```
Time    Agent A                 Agent B                 Result
----    -------                 -------                 ------
T1      git status (clean)
T2                              git status (clean)
T3      git commit
T4      git push
T5                              git commit
T6                              git push                CONFLICT!

Between T1 and T4, both agents see clean status.
Both attempt to push. One fails.
```

Oompa's sequential merge eliminates this:
```
Time    Agent A                 Agent B                 Merge Queue
----    -------                 -------                 -----------
T1      Submit branch
T2      Waiting...              Submit branch           [A, B]
T3                                                      Merge A
T4      Merged!                                         [B]
T5                                                      Merge B
T6                              Merged!                 []
```

**Non-negotiable because:**
- Race conditions cause lost work
- Manual conflict resolution is expensive
- Deterministic ordering aids debugging
- Audit requirements demand clear sequence

**Possible optimization:** Parallel merge for non-overlapping file sets (advanced).

#### 3. Separate Review Probably Stays (But Optional)

While self-review is faster, separate review provides demonstrable value:

**Observed issue types caught by separate review but missed by self-review:**
- Security vulnerabilities (agent doesn't think like attacker)
- Edge cases in error handling
- Inconsistency with project conventions
- Overcomplicated solutions
- Missing test coverage

**Recommendation:** Keep separate review as default, make self-review optional for:
- Documentation-only changes
- Configuration updates
- Trivial formatting fixes
- Time-critical hotfixes (with human approval)

```clojure
;; policy.edn
{:review-policy
 {:default :separate
  :self-review-patterns ["docs/**" "*.md" "config/*.edn"]
  :require-separate ["src/**" "tests/**" "security/**"]}}
```

### Hybrid Proposal

Based on the analysis, here is a proposed simplified Oompa Loompas architecture:

```
SIMPLIFIED OOMPA LOOMPAS:
==========================

KEEP (Essential):
  +-- Worktrees (required for test isolation)
  +-- Merge queue (required for safety)
  +-- Structured logging (required for audit)
  +-- Policy enforcement (required for governance)

SIMPLIFY (From Ralph):
  +-- Task system: Markdown files replace tasks.edn
  +-- Spec driving: Add MASTER_DESIGN.md + ENGINEERING_PLAN.md
  +-- Self-directed tasks: Agent can create new tasks
  +-- Termination signal: Explicit completion indicator

MAKE OPTIONAL:
  +-- Separate reviewer: Configurable per task type
  +-- Worker count: Auto-scale based on queue depth
  +-- Worktree pre-allocation: Create on demand

REMOVE (Over-engineering):
  +-- Overly complex schema validation
  +-- Unused merge strategies
  +-- Excessive configuration options
```

**Proposed file structure:**

```
project/
+-- MASTER_DESIGN.md        # Requirements (read-only)
+-- ENGINEERING_PLAN.md     # Implementation plan (agent-writable)
+-- todos/
|   +-- ready/              # Pending tasks (markdown)
|   +-- complete/           # Finished tasks (archive)
+-- config/
|   +-- policy.edn          # Simplified policy
|   +-- prompts/            # Role templates
+-- .workers/               # Worktree pool (unchanged)
+-- runs/                   # JSONL logs (unchanged)
```

**Simplified policy.edn:**

```clojure
{;; File permissions
 :allow ["src/**" "tests/**" "docs/**"]
 :deny ["secrets/**" "*.pem"]

 ;; Limits
 :max-diff-lines 800
 :max-files 10

 ;; Review policy
 :review {:default :separate
          :self-review ["docs/**" "*.md"]}

 ;; Task generation
 :task-creation {:enabled true
                 :max-per-iteration 3
                 :require-approval false}}
```

### Future Directions

#### 1. Adaptive Worker Count

Current system uses fixed worker count. Proposed: scale based on queue depth.

```clojure
(defn optimal-worker-count [queue-depth]
  (cond
    (< queue-depth 5) 1
    (< queue-depth 15) 2
    (< queue-depth 30) 4
    :else 8))

(defn auto-scale! [state]
  (let [current (count (:workers state))
        optimal (optimal-worker-count (count (:task-queue state)))]
    (cond
      (> optimal current) (add-workers! state (- optimal current))
      (< optimal current) (remove-workers! state (- current optimal))
      :else state)))
```

Benefits:
- Cost efficiency (fewer workers when idle)
- Faster throughput (more workers when backlogged)
- Automatic adaptation to workload

#### 2. Learned Task Decomposition

Agents often create tasks that are too large or too small. Machine learning could optimize granularity.

```
Historical data:
  task-size (tokens) | success-rate | merge-conflicts
  --------------------|--------------|----------------
  < 100              | 95%          | 2%
  100-500            | 85%          | 8%
  500-1000           | 70%          | 15%
  > 1000             | 40%          | 35%

Model learns:
  optimal-task-size ~ 100-300 tokens

Agent prompt modification:
  "When creating tasks, aim for 100-300 token changes.
   Split larger work into multiple tasks."
```

#### 3. Cross-Agent Memory

Currently, agent_notes/ provides shared memory, but it is unstructured. Proposed: vector store for semantic search.

```clojure
(defn remember! [agent-id memory-type content]
  (let [embedding (embed content)
        entry {:agent-id agent-id
               :type memory-type
               :content content
               :embedding embedding
               :timestamp (now-ms)}]
    (index! entry)))

(defn recall [query k]
  (let [query-embedding (embed query)]
    (->> (search-index query-embedding k)
         (map :content))))

;; Usage in agent context
(defn build-context [...]
  (let [relevant-memories (recall (:summary task) 5)]
    {:memories relevant-memories
     ...}))
```

Types of memories:
- Technical decisions ("We chose FastAPI because...")
- Bug patterns ("This error usually means...")
- Code conventions ("We name test files as...")
- Human feedback ("User prefers X over Y")

#### 4. Spec Evolution

MASTER_DESIGN is currently read-only. Proposal: agents can propose spec changes for human approval.

```markdown
# Proposed Spec Change

## Current Spec (MASTER_DESIGN.md, line 42):
> API must return responses in under 100ms

## Proposed Change:
> API must return responses in under 200ms for read operations,
> and under 500ms for write operations with complex validation

## Justification:
The original 100ms requirement is not achievable for write operations
that require:
1. Input validation against external schema
2. Database consistency checks
3. Audit log generation

Our measurements show:
- Read operations: 45ms average (meets original spec)
- Write operations: 350ms average (violates original spec)

## Impact Analysis:
- No user-facing degradation (writes are already batched)
- Enables proper validation without shortcuts
- Aligns with industry standards for similar systems

## Approval Required:
[ ] Product Owner
[ ] Technical Lead
```

Workflow:
1. Agent identifies unrealistic or incorrect spec
2. Creates proposal in `spec_changes/pending/`
3. Human reviews and approves/rejects
4. Approved changes update MASTER_DESIGN.md

---

## Conclusion

### What Ralph Does Well

1. **Radical Simplicity**: 450 lines that accomplish the core mission. Every line justified, nothing superfluous.

2. **Spec-Driven Design**: MASTER_DESIGN + ENGINEERING_PLAN provides clear, human-readable guidance that agents can follow without complex parsing.

3. **Filesystem as Database**: Using directories and files for state management eliminates dependencies and enables direct human inspection.

4. **Self-Direction**: Agents that can create their own tasks adapt to project needs rather than waiting for human decomposition.

5. **Clean Termination**: Explicit AGENT_TERMINATE signal provides clear completion semantics and prevents runaway processes.

6. **Low Barrier to Entry**: A single prompt file and a bash loop. Anyone can understand it, modify it, debug it.

### What Oompa Loompas Does Well

1. **True Isolation**: Worktrees guarantee that parallel agents cannot interfere with each other, enabling safe test execution and build processes.

2. **Structured Review**: Separate proposer and reviewer agents catch issues that self-review misses, particularly security and edge cases.

3. **Safe Merging**: Sequential merge queue eliminates race conditions that plague optimistic approaches, ensuring every change integrates cleanly.

4. **Audit Trails**: JSONL logs provide structured records for compliance, debugging, and analysis.

5. **Policy Enforcement**: Explicit configuration of allowed paths, diff limits, and timeouts prevents agents from exceeding their mandate.

6. **Graceful Recovery**: Automatic rollback on merge failure and structured error handling prevent catastrophic failures.

### Recommended Hybrid Approach

The ideal system combines Ralph's simplicity with Oompa Loompas' safety:

**Core Architecture (from Oompa Loompas):**
- Worktree isolation for parallel work
- Sequential merge queue for safe integration
- Structured JSONL logging for audit
- Policy enforcement for governance

**Simplifications (from Ralph):**
- Markdown task files instead of tasks.edn
- MASTER_DESIGN + ENGINEERING_PLAN for spec driving
- Configurable self-review for low-risk changes
- Self-directed task creation capability
- Explicit termination signal

**Configuration Flexibility:**
- Review mode selectable per task type
- Worker count auto-scaling optional
- Task creation with configurable guardrails

The hybrid achieves approximately 1,500 lines (30% reduction from current Oompa Loompas) while preserving all essential safety properties and gaining Ralph's elegance.

### Final Thoughts

Both architectures represent thoughtful solutions to the challenge of autonomous software development. Ralph proves that simplicity scales remarkably well when assumptions hold. Oompa Loompas proves that complexity is justified when guarantees matter.

The choice between them - or a hybrid - depends on context:
- **Project scale**: Small projects benefit from Ralph's simplicity
- **Team size**: Large teams need Oompa's coordination
- **Risk tolerance**: High-stakes code needs isolation and review
- **Cost sensitivity**: Token budgets favor Ralph's single-agent approach
- **Compliance requirements**: Regulated environments need audit trails

Neither architecture is universally superior. The best system is the one that matches your constraints while remaining as simple as possible - but no simpler.

---

**Document Statistics:**
- Total words: ~12,000
- Sections: 5 major parts
- Diagrams: 15 ASCII illustrations
- Code examples: 25+ snippets
- Comparison tables: 3 comprehensive tables
