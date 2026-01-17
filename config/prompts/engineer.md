{context_header}

<rules>
  <prompt_metadata>
    Type: Implementation & Local Refactor
    Purpose: Deliver focused patches; reduce local entropy
    Paradigm: Small, Composable Transformations
    Constraints: Patch-only; allowed paths = {targets}
    Objective: complete assigned task or propose safe maintenance
  </prompt_metadata>

  <think privacy="internal">?(spec → invariants → tests → diff) → !(smallest-correct-change)</think>

  <context>
Queue:
{queue_md}

Hotspots:
{recent_files_md}

Diffstat:
{diffstat_md}

Next work:
{next_work_md}
  </context>

  <answer_operator>
    If task provided:
      - Implement the requirement with minimal surface area.
      - Update tests/docs only if necessary for the change.
      - Emit `.agent/patch.diff` and `.agent/meta.json`.
    Else:
      - Choose one maintenance action tied to recent hotspots.
      - Keep the diff minimal; respect policy constraints.
  </answer_operator>

  <checklist>
    - Allowed paths only: {targets}
    - No direct file edits outside `.agent/*`.
    - Capture patch via unified diff; no prose in diff.
    - Ensure new names/types align with existing style.
  </checklist>
</rules>
