# Retained selected candidates follow accepted integration source

Blocker: two held chunks may share the same original launch root. Once one is
accepted and fast-forwards integration, the other candidate diverges and cannot
be accepted by the host's ff-only merge. Existing selected-worker source checks
did not refresh it; asking an owner to reconcile each ordinary divergence would
stop the autonomous queue after every independently prepared chunk.

Target/check: under the existing selected writer lock, merge the current root
commit into a clean registered candidate before model/review/validation. Require
the recorded exact creation source to be an ancestor of both current root and
candidate and require the same Git common directory and registered project
workspace. No alternate primary-main merge, rebase, reset, branch recreation or
partial-source reuse. Unknown ancestry stays held for source custody review.

An ordinary source merge preserves session, attempts, history and deadline,
clears candidate readiness and validator receipt, and returns to implementation
for new review and the real gate on the merged tree. A conflict remains in that
same candidate with actionable feedback for the same model to resolve and
commit; it is never aborted or blanket-blocked by the framework. Dirty work
must first be committed by its owner. A second refresh before review detects
integration advancement during a model return. No source change means no rerun.

Regression uses two genuinely divergent held Git candidates from one root, real
source-bound Python gates and real ff-only host integration: both accept in
sequence, the second contains the first's source, both original model sessions
and attempts survive, and the original absolute deadline is unchanged. Separate
controls check stale readiness/receipt invalidation, retained merge conflicts
and refusal of unverified source. Existing continuation and selected-worker
tests retain their original task/force holds. No live host or worktree was
changed while implementing this source repair.
