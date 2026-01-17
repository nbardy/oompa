---
title: Context Header Overhaul – Goals & Requirements
date: 2025-10-29
status: green
owners:
  - codex
---

# Why

The previous “ACH-lite” context block was vague, diverged between prompts, and
forced each role to guess at repo status. Agents frequently asked clarifying
questions because critical facts (pending reviews, queue state, hotspots) lived
in prose elsewhere. We need a compact, deterministic header injected ahead of
every prompt so CTO/Engineer/Reviewer agents start with the same situational
awareness.

# Goals

1. **Single Source Context**  
   Derive all prompt tokens (YAML header + Markdown snippets) from one helper so
   every role sees the same repo state snapshot.

2. **High-Signal Header**  
   Keep the header ≤20 lines with the following fields:
   - repo branch/head and recency window
   - pending ready-for-review notes (id, age, up to 3 files)
   - backlog tasks (id + one-line summary)
   - hotspots touched within recent-sec seconds (path + age)
   - allowed targets list and policy rules
   - orchestrator-curated next_work suggestions (≤5 items)
   - mode flag (`propose`/`review`) so CTO knows how to behave

3. **Deterministic Ordering**  
   Sort lists by recency/priority, dedupe file paths, and cap each section at
   seven entries so prompts don’t balloon as the repo evolves.

4. **Reusable Metadata**  
   Keep legacy tokens (`queue_md`, `recent_files_md`, etc.) available for the
   rest of each prompt body so we don’t regress formatting elsewhere.

5. **Zero Manual Editing**  
   Context should reflect the live filesystem state (agent_notes, tasks.edn,
   git) every time a task runs—no hand-maintained docs.

# Requirements

- Build helper functions inside `agentnet/src/agentnet/core.clj`.
- Inject `{context_header}` token at the very top of `config/prompts/{engineer,cto,reviewer}.md`.
- Update blueprint docs so the new flow is discoverable.
- Must run without additional CLI tools beyond Babashka + git.
- Keep YAML ASCII-only; models parse it more reliably.

# Success Criteria

- Running `bb context` prints the YAML header.
- Every prompt invocation receives the header (verified via `agentnet.bb` logs).
- Agents no longer receive stale queue/hotspot data; sections update when notes
  or tasks change.

# Non-Goals

- No change to Codex exec command, review process, or pending-note format.
- No introduction of remote data sources (still filesystem-only).
