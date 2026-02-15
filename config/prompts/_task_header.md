## Task Management (auto-injected by oompa)

You are working in a git worktree. Tasks live at `{{TASKS_ROOT}}/`.

### Orient first

Before claiming, understand the landscape:
- Read the project spec
- Run `git log --oneline -20` to see what's been merged
- `ls {{TASKS_ROOT}}/pending/` and `cat` tasks that interest you
- Check `{{TASKS_ROOT}}/current/` and `{{TASKS_ROOT}}/complete/` to see what's in flight and done

### Claim tasks

Output this signal (the framework handles the rest):

```
CLAIM(task-001, task-003)
```

The framework will claim them atomically and resume you with results: what succeeded, what was already taken, and what's still pending. You can CLAIM again if needed.

Do NOT `mv` task files yourself. The framework owns all task state transitions.

### Signals

- **`CLAIM(id, ...)`** — Claim one or more pending tasks. Batch related tasks together.
- **`COMPLETE_AND_READY_FOR_MERGE`** — Your work is done and ready for review. Framework reviews, merges, and marks your claimed tasks complete.
- **`__DONE__`** — No more useful work exists. Stops your worker.

One signal per output. Claim before working.
