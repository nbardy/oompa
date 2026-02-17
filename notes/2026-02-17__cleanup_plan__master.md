# Master Cleanup Plan: Unify Observability

2026-02-17 — static, written once

## Root Cause

The event-sourced rework (commit `015d06b`) was fully implemented in the oompa
codebase. The current code writes ONLY the new format (`started.json`, `stopped.json`,
`cycles/`, `reviews/`). However:

1. **Stale disk state**: 51+ runs across 5 projects were created by older npm versions
   and use the old format (`run.json`, `iterations/`, `summary.json`). The web view
   can't determine liveness for these → shows "3 running" forever.

2. **Schema gap**: `cycle.schema.json` missing `"claimed"` outcome that the code writes.

3. **Dead code**: `orchestrator.clj` still has old JSONL writers, reachable from the
   simple-mode fallback in `cmd-run`/`cmd-loop`.

## Implementation Tasks (5 parallel agents)

### Agent 1: Nuke stale runs
- `rm -rf` all runs/ directories across 5 projects
- See `notes/2026-02-17__cleanup_plan__stale_disk_state.md`

### Agent 2: Fix cycle schema + codegen
- Add `"claimed"` to `schemas/cycle.schema.json` enum
- Run `npx tsx tools/gen-oompa-types.ts` in claude-web-view to regenerate types

### Agent 3: Fix web-view normalizeStatus
- Add `status === 'claimed'` to the running branch in `server.ts:1462-1469`

### Agent 4: Retire dead orchestrator code
- Replace `orchestrator/run-once!` call in `cli.clj:229` with error + exit
- Replace `orchestrator/run-loop!` call in `cli.clj:265` with error + exit
- Add dead-code comment to `orchestrator.clj:289`

### Agent 5: Verify + publish
- `bb -e '(load-file ...)'` for all 3 core .clj files
- `npm publish` the updated package
- Runs LAST (after agents 2-4 complete)

## Dependency Graph

```
Agent 1 ─────────────────────────────────── (independent, can run first)
Agent 2 ──→ Agent 5 (schema must be fixed before publish)
Agent 3 ─────────────────────────────────── (independent, different repo)
Agent 4 ──→ Agent 5 (clj changes must be done before publish)
```

## Sub-plans

- [Stale disk state](2026-02-17__cleanup_plan__stale_disk_state.md)
- [Code fixes](2026-02-17__cleanup_plan__code_fixes.md)
