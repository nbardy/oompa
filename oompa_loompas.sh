#!/bin/bash
for w in $(seq 1 ${WORKERS:-3}); do
  (for i in $(seq 1 ${ITERATIONS:-5}); do
    wt=".w${w}-i${i}"
    git worktree add $wt -b $wt 2>/dev/null
    { echo "Worktree: $wt"; cat prompts/worker.md; } | claude -p -
  done) &
done; wait
