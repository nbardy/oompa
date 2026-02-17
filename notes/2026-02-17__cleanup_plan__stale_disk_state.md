# Stale Oompa Run Data Cleanup Plan

2026-02-17 — static, written once

## Background

The oompa orchestrator was refactored (commit `015d06b`) to write event-sourced
run artifacts:

    runs/<id>/started.json    ← has PID; liveness = PID alive + no stopped.json
    runs/<id>/stopped.json    ← written on clean exit
    runs/<id>/cycles/
    runs/<id>/reviews/

51+ runs across five projects still use the OLD format:

    runs/<id>/run.json        ← no PID, no liveness signal
    runs/<id>/iterations/
    runs/<id>/summary.json

The web view cannot determine liveness for old-format runs and surfaces them as
"running". No oompa processes are actually running as of this writing.

## What to Delete

Everything inside each project's `runs/` directory. These are completed historical
runs with no ongoing processes and no recovery value.

Also covered by `rm -rf runs/`:
- `room-runners-arena-lib/runs/*.pid` — stale PID files from overnight script
- `room-runners-arena-lib/runs/*.log` — stale log files from overnight script

## What to Preserve

Nothing.

## Exact Commands

```bash
rm -rf \
  /Users/nicholasbardy/git/llm_trader/runs/ \
  /Users/nicholasbardy/git/room-runners-arena-lib/runs/ \
  /Users/nicholasbardy/git/image_magick/runs/ \
  /Users/nicholasbardy/git/web_agency/runs/ \
  /Users/nicholasbardy/git/ai_researcher/runs/
```

## gitignore Status

| Project                  | `runs/` gitignored? | Action needed        |
|--------------------------|---------------------|----------------------|
| llm_trader               | Yes                 | None                 |
| room-runners-arena-lib   | Yes                 | None                 |
| web_agency               | Yes                 | None                 |
| image_magick             | **No**              | Add `runs/` to .gitignore |
| ai_researcher            | **No .gitignore**   | Create .gitignore    |
| oompa_loompas            | Yes                 | None                 |
