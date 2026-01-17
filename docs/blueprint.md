# AgentNet Harness Blueprint (Babashka Edition)

## Overview

`agentnet.bb` is a single Babashka script orchestrating Codex/Claude subprocesses.
It keeps the control plane lightweight: load config, build an ACH-lite context,
launch proposer/reviewer loops via `core.async`, and optionally merge approved
patches with git.

```
agentnet.bb
 ├─ loads config/tasks.edn & prompts/*.md
 ├─ gathers note hotspots via agentnet/notes.clj
 ├─ builds ACH context pack (queue, hotspots, next work)
 ├─ schedules tasks on a core.async channel (workers configurable)
 └─ workers run proposer → reviewer via `codex exec`, enforce policy, merge
```

## Key files

| Path | Purpose |
|------|---------|
| `agentnet.bb` | Main Babashka orchestrator (run/lint/context/status). |
| `agentnet/src/agentnet/notes.clj` | Filesystem note helpers. |
| `config/tasks.edn` | Seed task definitions (id, summary, targets). |
| `config/policy.edn` | Allow/deny globs, diff thresholds, timeouts. |
| `config/prompts/*.md` | Prompt templates with `{ach_yaml}` and metadata tokens. |
| `agent_notes/` | Source for hotspots/review feedback. |
| `runs/run-*.jsonl` | JSONL logs per orchestrator run. |

## Workflow

1. `bb run [--workers N] [--dry-run]`
   - Builds context pack and pushes tasks to worker pool.
   - Engineer prompt emits `.agent/patch.diff`; reviewer prompt validates.
   - Policy (allow/deny, max diff) enforced before merge.
   - Approved tasks merge via `git apply --index && git commit` (unless dry-run).
   - Run summary saved to `runs/run-<timestamp>.jsonl`.
2. `bb lint-notes` validates note front matter (`:id`, status, etc.).
3. `bb context` prints the current ACH context for debugging prompts.
4. `bb status` prints a quick summary of the most recent run log.

## Extending

- Swap CLIs: modify `run-codex` to call another agent binary.
- Tighten policy: add hooks to inspect `git diff --numstat`, deny binary edits, etc.
- Enhance logging: forward JSONL events to dashboards or Slack/webhooks.
- Auto-retries: wrap `process-task` with bounded attempt logic per task.
- Multi-role prompts: use extra entries in `config/tasks.edn` (`:cli`, `:cwd`, etc.).

This structure keeps the control plane small while relying on strong guardrails
(policy, logging, minimal state) and fast startup via Babashka.
