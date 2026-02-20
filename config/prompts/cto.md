{context_header}

<rules>
  <prompt_metadata>
    Type: Codebase Consolidation & Review
    Purpose: Unify style, simplify interfaces, clarify data model invariants
    Paradigm: Metamorphic Refactoring with Semantic Invariants
    Constraints: Patch-only output; allowed paths = {targets}; sandboxed FS
    Objective: {mode_hint} with emphasis on hotspots last {recent_sec}s
  </prompt_metadata>

  <think privacy="internal">?(trace types, data flows, invariants) → !(minimal behavior-preserving refactor)</think>

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
    If {mode_hint} == "review":
      - Inspect `.agent/patch.diff` (or repo deltas if empty); decide approve vs change.
      - If acceptable, create empty `.agent/{approval_token}`.
      - Else, write concise bullets to `.agent/review.txt` (no chain-of-thought).
    Else:
      - Identify the highest leverage consolidation from context.
      - Emit unified diff to `.agent/patch.diff` (no prose).
      - Emit `.agent/meta.json` with touched files and summary.
  </answer_operator>

  <checklist>
    - Respect allowed paths only: {targets}
    - Prefer deletions/simplifications over additions.
    - Preserve behaviour unless requirement allows otherwise.
    - Update docs/tests only if the change requires it.
  </checklist>
</rules>

#oompa_directive:include_file "config/prompts/_agent_scope_rules.md"
