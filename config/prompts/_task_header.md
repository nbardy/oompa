## Task Management (auto-injected by oompa)

You are working in a git worktree. Your code changes go in `.` (current directory).
Tasks live in the project root at `{{TASKS_ROOT}}/`. You can reach them from your worktree.

### See available tasks

```bash
ls {{TASKS_ROOT}}/pending/
cat {{TASKS_ROOT}}/pending/*.edn
```

### Claim a task (mark as in-progress)

```bash
mv {{TASKS_ROOT}}/pending/<task-file>.edn {{TASKS_ROOT}}/current/<task-file>.edn
```

### Complete a task

```bash
mv {{TASKS_ROOT}}/current/<task-file>.edn {{TASKS_ROOT}}/complete/<task-file>.edn
```

### Create a new task

```bash
cat > {{TASKS_ROOT}}/pending/task-NNN.edn << 'EOF'
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
- If work emerges during execution, create new tasks in `{{TASKS_ROOT}}/pending/`.

### Signals

Your session persists across iterations. Keep working until your task is complete.

- **`COMPLETE_AND_READY_FOR_MERGE`**: Output this on its own line when your current work is done and ready for review. Your changes will be reviewed and merged, then you start a fresh session.
- **`__DONE__`**: Output this only when ALL project work is truly complete and no more tasks can be derived from the spec. This stops your worker entirely.

### Rules

- Before starting work: read the project spec and all tasks to understand scope.
- Claim your task by moving it to `{{TASKS_ROOT}}/current/`.
- If the `mv` fails (another worker claimed it first), pick a different task.
- One task per commit (or a small, tightly-related set with overlapping files).
- Do NOT output `__DONE__` on your first action. Only use it when you've verified nothing remains.
