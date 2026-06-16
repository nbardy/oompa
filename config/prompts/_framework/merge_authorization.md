## Merge Authorization

Your work has been reviewed and approved. You are now authorized to merge to main.

Steps:
1. Commit any uncommitted changes in your worktree (git add -A && git commit -m 'wip')
2. In the project root ({project_root}), run:
   git checkout main && git merge {wt_branch} --no-edit
3. If there are merge conflicts: resolve them, then git add -A && git commit --no-edit
4. After the merge succeeds, get the commit SHA: git rev-parse --short HEAD
5. Signal MERGE_COMPLETE(sha) — e.g. MERGE_COMPLETE(a3f7d2c)

IMPORTANT: Only signal MERGE_COMPLETE after the merge is actually on main.
If you cannot resolve conflicts after trying hard, signal NEEDS_FOLLOWUP with details.