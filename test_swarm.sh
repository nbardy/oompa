#!/bin/bash
# Quick integration test for the swarm orchestrator

set -e
cd "$(dirname "$0")"

echo "=== AgentNet Swarm Integration Test ==="
echo

# Test 1: Modules load
echo "1. Testing module loading..."
bb -e "(require '[agentnet.cli :as cli]) (println \"  ✓ All modules loaded\")"

# Test 2: Help command
echo "2. Testing help command..."
bb -e "(require '[agentnet.cli :as cli]) (cli/-main \"help\")" > /dev/null
echo "  ✓ Help command works"

# Test 3: Check backends
echo "3. Checking agent backends..."
bb -e "(require '[agentnet.cli :as cli]) (cli/-main \"check\")" 2>/dev/null | grep -q "available" && echo "  ✓ At least one backend available"

# Test 4: Worktree creation (requires git repo)
echo "4. Testing worktree support..."
if git rev-parse --git-dir > /dev/null 2>&1; then
  bb -e "
  (require '[agentnet.worktree :as wt])
  (let [config {:worker-count 2 :agent-type :claude :worktree-root \".workers\"}]
    (println \"  Creating 2 worktrees...\")
    (wt/init-pool! config)
    (println \"  ✓ Worktree pool created\"))
  " 2>/dev/null

  # Test 5: List worktrees
  echo "5. Testing worktree list..."
  bb -e "(require '[agentnet.cli :as cli]) (cli/-main \"worktrees\")" 2>/dev/null | head -5

  # Test 6: Cleanup
  echo "6. Cleaning up worktrees..."
  bb -e "(require '[agentnet.cli :as cli]) (cli/-main \"cleanup\")" 2>/dev/null
  echo "  ✓ Cleanup complete"
else
  echo "  ⚠ Skipping worktree tests (not a git repo)"
  echo "  To test worktrees, run: git init"
fi

echo
echo "=== All tests passed! ==="
echo
echo "To run the swarm:"
echo "  ./swarm.bb run --claude --workers 2"
echo "  ./swarm.bb run --codex --dry-run"
echo "  ./swarm.bb loop 5 --claude"
