# Harness System

Oompa supports multiple CLI agent backends ("harnesses"). All harness-specific knowledge lives in a single registry: `agentnet/src/agentnet/harness.clj`.

## Supported Harnesses

| Harness | Binary | Model Format | Example |
|---------|--------|-------------|---------|
| `codex` | `codex` | `codex:gpt-5.3-codex:medium` | OpenAI Codex CLI |
| `claude` | `claude` | `claude:opus` | Anthropic Claude Code |
| `opencode` | `opencode` | `opencode:opencode/kimi-k2.5-free` | Opencode CLI |
| `gemini` | `gemini` | `gemini:gemini-3-pro-preview` | Google Gemini CLI |

## Usage in oompa.json

```json
{
  "workers": [
    {"model": "claude:opus", "prompt": ["prompts/planner.md"], "count": 1},
    {"model": "codex:gpt-5.3-codex:medium", "prompt": ["prompts/executor.md"], "count": 2, "can_plan": false},
    {"model": "gemini:gemini-3-pro-preview", "prompt": ["prompts/executor.md"], "count": 1, "can_plan": false}
  ]
}
```

Model string format: `harness:model` (codex also supports `harness:model:reasoning`).

## Gemini Models

Working model names (as of 2026-02):

| Model | Status |
|-------|--------|
| `gemini-3-pro-preview` | works |
| `gemini-3-flash-preview` | works |
| `gemini-2.5-pro` | works |
| `gemini-2.5-flash` | works |

Note: Gemini 3 models require the `-preview` suffix. `gemini-3-pro` without it returns 404.

## Architecture: The Harness Registry

`harness.clj` contains a `registry` map — one entry per harness. Every harness-specific fact (binary name, CLI flags, stdin behavior, session strategy, output format) is data in this map:

```clojure
(def registry
  {:codex   {:binary "codex",   :prompt-via :cli-sep,  :stdin :close,   :session :uuid,      ...}
   :claude  {:binary "claude",  :prompt-via :flag,     :stdin :prompt,  :session :uuid,      ...}
   :opencode {:binary "opencode", :prompt-via :cli-arg, :stdin :close,  :session :extracted, ...}
   :gemini  {:binary "gemini",  :prompt-via :flag,     :stdin :close,   :session :implicit,  ...}})
```

Builder functions read from this map with zero structural branching:

- `(harness/build-cmd :gemini {:model "gemini-3-pro-preview" :prompt "do stuff"})` → command vector
- `(harness/process-stdin :gemini prompt)` → `""` (close stdin)
- `(harness/parse-output :gemini raw-output session-id)` → `{:output text :session-id id}`
- `(harness/make-session-id :gemini)` → `nil` (implicit sessions)
- `(harness/build-probe-cmd :gemini "gemini-3-pro-preview")` → probe command

## Adding a New Harness

1. Add one map entry to `registry` in `harness.clj`
2. Add the keyword to `agent-types` in `schema.clj`
3. Add a `build-command` method in `agent.clj` (for the older orchestrator code path)
4. Add a case to `check-available` in `agent.clj`

That's it — worker.clj, cli.clj, and all command building derives from the registry automatically.

## Key Differences Between Harnesses

| Feature | codex | claude | opencode | gemini |
|---------|-------|--------|----------|--------|
| Prompt delivery | CLI arg after `--` | `-p` flag (stdin) | CLI arg | `-p` flag value |
| Auto-approve | `--dangerously-bypass-...` | `--dangerously-skip-permissions` | (none) | `--yolo` |
| Session resume | Not supported | `--session-id ID --resume` | `-s ID --continue` | `--resume latest` |
| Structured output | Plain text | Plain text | NDJSON (`--format json`) | Plain text |
| Model flag | `--model` | `--model` | `-m` | `-m` |

## Session Strategies

- **`:uuid`** (codex, claude): Generate a UUID. Claude uses it with `--session-id`; codex generates but can't resume.
- **`:extracted`** (opencode): No pre-generated ID. Session ID is parsed from NDJSON output after first run, then reused with `-s ID --continue`.
- **`:implicit`** (gemini): Sessions are project-scoped by working directory. Resume with `--resume latest` — each worker's worktree isolates its session automatically.
