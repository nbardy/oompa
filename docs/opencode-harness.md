# Opencode Harness Behavior

This document describes the exact behavior used by Oompa workers when `harness: opencode`.

## Worker Run Command

Workers invoke:

```bash
opencode run --format json [--attach <url>] [-m <provider/model>] "<tagged prompt>"
```

Where the prompt starts with:

```text
[oompa:<swarmId>:<workerId>] ...
```

## Session Continuation

Session continuity is bound to each worker's own run output:

1. First iteration runs without `--session`.
2. Oompa parses the `sessionID` emitted by that same `opencode run --format json` invocation.
3. Resume iterations use that captured ID:

```bash
opencode run --format json -s <captured-session-id> --continue ...
```

Oompa does not call `opencode session list` to pick a global "latest" session.

## Done/Merge Signals

Oompa extracts assistant text from JSON `text` events and checks for:

- `COMPLETE_AND_READY_FOR_MERGE`
- `__DONE__`

This keeps existing worker done/merge behavior while using JSON mode.

## Attach Mode

If `OOMPA_OPENCODE_ATTACH` or `OPENCODE_ATTACH` is set, Oompa appends:

```bash
--attach <url>
```
