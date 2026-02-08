## Task Management (auto-injected by oompa)

You are working in a git worktree. Your code changes go in `.` (current directory).
Tasks live in the project root at `../tasks/`. You can reach them from your worktree.

### See available tasks

```bash
ls ../tasks/pending/
cat ../tasks/pending/*.edn
```

### Claim a task (mark as in-progress)

```bash
mv ../tasks/pending/<task-file>.edn ../tasks/current/<task-file>.edn
```

### Complete a task

```bash
mv ../tasks/current/<task-file>.edn ../tasks/complete/<task-file>.edn
```

### Create a new task

```bash
cat > ../tasks/pending/task-NNN.edn << 'EOF'
{:id "task-NNN"
 :summary "Short imperative description"
 :description "What needs to happen and why"
 :difficulty :easy  ;; :easy :medium :hard
 :files ["src/relevant-file.py"]
 :acceptance ["Specific condition that means done"]}
EOF
```

### Planning vs Executing

**WHEN PLANNING** (task queue is empty or nearly empty):
- Your FIRST priority is creating tasks for other workers. They are waiting.
- Read the project spec, identify gaps, and create 5-10 focused, well-detailed tasks.
- Do NOT execute tasks in the same iteration you create them.
- Commit the task files and finish your iteration so others can claim them immediately.

**WHEN EXECUTING** (tasks exist in pending):
- Claim one task, execute it end-to-end, complete it.
- If work emerges during execution, create new tasks in `../tasks/pending/`.

### Rules

- Before starting work: read the project spec and all tasks to understand scope.
- Claim your task by moving it to `../tasks/current/`.
- If the `mv` fails (another worker claimed it first), pick a different task.
- One task per commit (or a small, tightly-related set with overlapping files).
- Only output __DONE__ if you have completed work AND no more tasks can be derived from the spec. Never __DONE__ on your first action.
