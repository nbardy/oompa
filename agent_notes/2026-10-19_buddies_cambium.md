# Buddy-task / Cambium fork

Owner: Project Lead. Requested by owner on October 19.

Target: Oompa uses scoped canonical Buddy tasks and Cambium workspaces, without
interrupting the live wave_sim beta executor. Local branch: buddies-cambium.

Blockers: filesystem tasks duplicate planning authority; forced stale-worktree
removal can discard state; no scoped Buddy transport currently wired into Oompa.

Cheapest checks: mocked MCP task CAS race/refusal tests, prompt/config integration,
Cambium command/lifecycle tests, then read-only scoped tasks and a disposable
worktree lifecycle. No model execution or beta migration until these succeed.

Pass: one winner claims an eligible task, owner is retained, completion enters
review rather than done, failed/expired authority never falls back to local tasks;
workspaces are created/removed through Cambium and unfinished work is retained.
Stop: missing runtime grant or unsupported task contract is explicit, not worked
around through owner HTTP credentials or direct database access. This proves
harness integration only, not beta completion or scientific acceptance.

## Result

Implemented a thin tasks dispatcher with separate filesystem and scoped MCP
adapters; no duplicate Buddy queue. Atomic revision claims preserve owner,
blocked/paused/dependency checks refuse work, and model/merge completion only
submits review. Full task criteria reach the worker. Canonical unfinished tasks
produce `awaiting-buddy-acceptance`, even when nothing is currently runnable.

Iteration/pool creation, status, cleanup and salvage now use Cambium. External
unique workspaces replace stale overwrite; clean state, real integration and
inactive ownership are checked before removal. Git verifies merge signals.

Validation: 37 focused tests / 167 assertions passed (Buddy transport/task,
prompt integration, Cambium lifecycle, existing prompt/reviewer checks).
A real disposable-repository Cambium create/list/remove passed. Read-only live
`./swarm.bb tasks --config TEMP_CONFIG` succeeded through the current scoped MCP
endpoint: existing disk task was blocked at revision 11, zero runnable tasks.
No real Buddy task was mutated and no model or new beta executor launched.

Broader status-transition suite: 29 tests / 92 assertions, three failures in
`merge-no-claim-under-cap-does-not-kill-worker`. The same three failures reproduce
when loading upstream HEAD's worker source, so this is an existing baseline
issue, not a new passing claim. Left unrelated cycle semantics unchanged.

Remaining before beta backend switch: provide supported grant renewal beyond
the current turn, prove one real task through consumer validation/owner acceptance
and next-task selection, and reconcile existing in-progress executions. This
adapter does not itself implement an independent beta validator or a 12-hour
autonomous controller. The existing wave_sim reef manager was still running
when checked and was not interrupted or migrated.
