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

### Rules

- Before starting work: read the project spec and all tasks to understand scope.
- First action: claim your task by moving it to `../tasks/current/`.
- If the `mv` fails (another worker claimed it first), pick a different task.
- One task per commit (or a small, tightly-related set with overlapping files).
- If tasks are missing or underspecified: stop and write tasks before coding.
- If work emerges during execution: create new tasks in `../tasks/pending/`.
- When all tasks are complete and the spec is satisfied, output: __DONE__
