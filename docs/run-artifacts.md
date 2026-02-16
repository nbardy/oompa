# Run Artifacts

Quick reference for the event files oompa writes. For the full design rationale, see [visibility_architecture.md](visibility_architecture.md). For the worker lifecycle, see [systems_architecture.md](systems_architecture.md).

## Layout

```
runs/{swarm-id}/
  started.json                        swarm began (config + PID)
  stopped.json                        swarm ended (absent while running)
  cycles/
    {worker-id}-c{N}.json             worker completed cycle N
  reviews/
    {worker-id}-c{N}-r{round}.json    reviewer judged cycle N, round R
```

## Schemas

```
schemas/
  started.schema.json
  stopped.schema.json
  cycle.schema.json
  review.schema.json
```

JSON Schema draft-07. Source of truth for both Clojure (writer) and TypeScript (reader via codegen).

## Updating schemas

1. Update write function in `agentnet/src/agentnet/runs.clj`
2. Update schema in `schemas/`
3. Regenerate types: `./server/node_modules/.bin/tsx tools/gen-oompa-types.ts` (in claude-web-view)
