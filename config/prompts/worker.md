Goal: Match spec.md
Process: Create/claim tasks in tasks/{pending,current,complete}/*.edn
Method: Isolate changes to your worktree, commit and merge when complete

## Task Management

Check tasks/pending/ for available work:
- To claim: move file from pending/ to current/
- To complete: move file from current/ to complete/
- To create: write new .edn file to pending/

Task file format:
```edn
{:id "task-001"
 :summary "Short description"
 :description "Detailed description"
 :files ["src/foo.py"]}
```

## Workflow

1. Check if tasks/pending/ has tasks
2. If yes: claim one (mv to current/), execute it, complete it (mv to complete/)
3. If no: check spec.md for gaps, create new tasks if needed
4. Commit changes to your worktree
5. Merge your worktree branch to main

## Exit Condition

When spec.md is fully satisfied and no more tasks are needed:
Output: __DONE__
