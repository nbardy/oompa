---
title: Context Header Injection – Design Notes
date: 2025-10-29
status: green
owners:
  - codex
---

# Overview

We replaced the ad-hoc ACH block with a reusable context builder that records
pending work, queue status, hotspots, and policy hints in one compact YAML
header. This file documents the architecture so future tweaks stay consistent.

# Architecture

```
agentnet.bb
  └─ process-task
       ├─ builds per-task context via agentnet.core/build-context
       ├─ tokenizes prompts with {context_header}, {queue_md}, etc.
       └─ runs engineer → reviewer subprocesses

agentnet/src/agentnet/core.clj
  ├─ format-ago: shared relative time helper
  ├─ pending-items / recent-notes: note scanners
  ├─ build-context: renders YAML + markdown snippets
  └─ exposes legacy tokens (queue_md, next_work_md, ...)
```

# Data Sources

- **Tasks** (`config/tasks.edn`): supplies backlog entries and suggested next
  work. Sorted by `(priority, id)` with a fallback priority of 1000 when absent.
- **Notes** (`agent_notes/...`): reused via `agentnet.notes` helpers. We read
  `ready_for_review`, `proposed_tasks`, `notes_FROM_CTO`, and `scratch` for
  pending items and hotspots.
- **Git metadata**: `git rev-parse` calls provide branch and short head so the
  header proves recency to the agent.
- **Policy** (`config/policy.edn`): turned into short strings
  (`allow:src/**`, `+≤200 lines`, etc.) and capped at seven entries.

# Rendering Rules

1. **Pending**  
   - Up to seven ready-for-review items.  
   - Each entry lists `id`, `age`, and up to three files (targets if present,
     else note path).  
   - Ages use `format-ago` (“just now”, “5m ago”, “2d ago”).

2. **Backlog**  
   - Up to seven `{id, summary}` pairs pulled directly from tasks.edn.  
   - JSON-like inline map formatting keeps it compact.

3. **Hotspots**  
   - Combines scratch + fresh notes touched within `recent-sec`.  
   - Deduped by file path, sorted by mtime.  
   - Each entry shows both path and relative age.

4. **Targets + Policy**  
   - `targets` list reflects the current task’s allowed paths (empty list = global).  
   - `policy` array merges default rules with allow/deny/limits from policy.edn.

5. **Next Work**  
   - Deterministic order: pending review reminder, top backlog task, top
     hotspot.  
   - Always at least one entry (`"noop"`) so YAML is syntactically valid.

6. **Mode**  
   - Accepts `propose` or `review`; sanitized to `propose` for any other value.

# Prompt Integration

- `{context_header}` is now the first token in `engineer.md`, `cto.md`, and
  `reviewer.md`.  
- Other placeholders (`queue_md`, `recent_files_md`, `next_work_md`,
  `diffstat_md`) still work, sourced from the same `build-context` call.
- `agentnet.bb` recomputes the header per task, inserting target-specific
  allowed paths so agents cannot wander outside their sandbox.

# Testing / Validation

- `bb context` prints the YAML header; inspect manually or pipe into `yq` for
  additional checks.  
- Insert temporary logging around `context_header` in `run-codex` if you need to
  prove prompts are seeded correctly.  
- When `bb` is unavailable (CI sandbox), run
  `bb.edn :context` locally—no other dependencies required.

# Future Enhancements

- Replace inline git calls with cached values when running multiple tasks to
  shave a few milliseconds.  
--Add diffstat/LOC metrics by invoking `git diff --stat` for each patch once
  `.agent/patch.diff` exists.  
- Consider exposing environment tags (sandbox mode, network policy) if Codex
  variants need them.

For now the design keeps the scaffolding simple, deterministic, and easy to
extend without touching every prompt again.
