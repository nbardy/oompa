# Run Artifacts

Oompa persists structured JSON artifacts under `runs/{swarm-id}/` during and after each swarm run. These files are consumed by `claude-web-view` for dashboards and analytics.

## Directory Layout

```
runs/{swarm-id}/
  run.json                            # written at swarm start
  live-summary.json                   # written after each iteration (overwritten)
  summary.json                        # written at swarm end
  iterations/
    {worker-id}-i{N}.json             # one per iteration, written at iteration end
  reviews/
    {worker-id}-i{N}-r{round}.json    # one per review round
```

All writes are atomic (write to `.tmp`, then rename) to avoid partial reads by dashboards.

## Artifact Descriptions

| File | When Written | Contents |
|------|-------------|----------|
| `run.json` | Swarm start | Start time, worker configs (harness, model, prompts, iterations), planner/reviewer config |
| `live-summary.json` | After each iteration | Per-worker metrics snapshot. Serialized with a lock to prevent concurrent corruption. **Deleted when `summary.json` is written** — its lifecycle ends at swarm completion |
| `summary.json` | Swarm end | Final per-worker stats (completed, merges, claims, rejections, errors, recycled) and aggregates |
| `iterations/{wid}-i{N}.json` | Iteration end | Outcome, duration, task ID, recycled tasks, error snippet, review round count, metrics |
| `reviews/{wid}-i{N}-r{round}.json` | After each review round | Verdict (approved/rejected/needs-changes), reviewer output, diff file list |

Source: `agentnet/src/agentnet/runs.clj`

## JSON Schema Contract

JSON Schemas in `schemas/` are the shared contract between oompa_loompas (writer) and claude-web-view (reader). Both projects must agree on these shapes.

When artifact shapes change:

1. Update the write functions in `agentnet/src/agentnet/runs.clj`
2. Update the corresponding schema in `schemas/`
3. Regenerate TS types in claude-web-view:
   ```bash
   npx tsx tools/gen-oompa-types.ts
   ```

## Raw vs. Runtime Types in claude-web-view

claude-web-view distinguishes between two layers of types:

**Raw JSON types** (e.g. fields from `run.json`, `live-summary.json`, `summary.json`) are the on-disk artifact shapes written by oompa. The server reads these files directly with `fs.readFileSync` and `JSON.parse`.

**Derived runtime types** (e.g. `OompaRuntimeSnapshot`, `OompaRuntimeRun`, `OompaRuntimeWorker`) are constructed by the server at request time. The `/api/swarm-runtime` endpoint synthesizes a runtime snapshot by:

- Reading `run.json` for worker configs and swarm metadata
- Reading `live-summary.json` for mid-run per-worker metrics
- Reading `summary.json` for final stats (when available)
- Reading review files to synthesize verdicts when `summary.json` is missing
- Checking process liveness (`live-summary.json` presence + PID checks)
- Combining all sources into a single `OompaRuntimeSnapshot` response

The runtime types are defined in `claude-web-view/shared/src/index.ts` and used by both server and client.
