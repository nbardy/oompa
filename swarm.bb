#!/usr/bin/env bb

;; AgentNet Swarm Orchestrator
;;
;; New modular entry point using worktree-based isolation.
;;
;; Usage:
;;   ./swarm.bb run              # Run all tasks once
;;   ./swarm.bb run --workers 4  # With 4 parallel workers
;;   ./swarm.bb run --claude     # Use Claude instead of Codex
;;   ./swarm.bb loop 20          # Run 20 iterations
;;   ./swarm.bb check            # Check agent backends
;;
;; See ./swarm.bb help for full usage.

(require '[agentnet.cli :as cli])

(apply cli/-main *command-line-args*)
