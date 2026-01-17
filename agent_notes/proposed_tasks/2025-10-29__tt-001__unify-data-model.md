---
id: tt-001
type: task
status: green
rank: 1
owner: cto-1
created_at: 2025-10-29T16:02:00Z
---

# Task: Unify data model across ingest pipeline

## Why
- Current pipeline branches create redundant schema migrations.
- Business analytics lag behind marketing data by 24h because of mismatched transformations.

## Definition of done
- Shared schema module extracted to `src/shared/schema.clj`.
- Backfill script migrates historical records without downtime.
- Dashboard query latency hits <250ms at p95.

## Next steps
1. Draft migration plan in scratch and schedule cross-team review.
2. Prototype schema module in feature branch `feat/unified-schema`.
3. Pair with data engineering on backfill rehearsal.
