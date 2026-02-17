# Observability Code Fixes Plan

2026-02-17 — static, written once

Five fixes to close out loose ends from the event-sourced rework.

## Fix 1 — Schema: add `"claimed"` to cycle outcome enum

**File:** `schemas/cycle.schema.json:13`

`worker.clj:678` emits `{:outcome :claimed}` on every CLAIM signal cycle.
The schema enum does not include it, making every claim cycle schema-invalid.

```diff
-"enum": ["merged", "rejected", "error", "done", "executor-done", "no-changes", "working"]
+"enum": ["merged", "rejected", "error", "done", "executor-done", "no-changes", "working", "claimed"]
```

## Fix 2 — server.ts: map `claimed` → `running` in `normalizeStatus`

**File:** `claude-web-view/server/src/server.ts` `normalizeStatus` function

`claimed` falls through to the `return 'starting'` default.
A worker that has claimed tasks is running, not starting.
Add `status === 'claimed'` to the running branch.

## Fix 3 — Regenerate TypeScript types

After Fix 1, run codegen so `OompaCycle.outcome` includes `'claimed'`.

```sh
cd /Users/nicholasbardy/git/claude-web-view
npx tsx tools/gen-oompa-types.ts
```

## Fix 4 — Dead orchestrator code

`orchestrator.clj` has `save-run-log!` writing old JSONL format.
Reachable only from `cmd-run`/`cmd-loop` simple-mode (no oompa.json).
Replace those callsites with an error message + `System/exit 1`.
Mark orchestrator functions as dead code with a comment.

## Fix 5 — Publish npm package

Verify parens, then `npm publish`:

```sh
bb -e '(load-file "agentnet/src/agentnet/worker.clj")'
bb -e '(load-file "agentnet/src/agentnet/orchestrator.clj")'
bb -e '(load-file "agentnet/src/agentnet/cli.clj")'
npm publish
```

## Execution order

1 → 3 (schema then codegen, dependent)
2 (independent)
4 (independent)
5 (last, after all clj edits)
