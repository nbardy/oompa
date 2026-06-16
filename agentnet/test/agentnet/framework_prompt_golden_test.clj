(ns agentnet.framework-prompt-golden-test
  "Text-identity guard for the behavioral framework prompts extracted out of
   agentnet.worker into config/prompts/_framework/*.md.

   The existing prompt-token-injection test pins the worker-assembly path but
   does NOT pin the body text of nudge / needs_followup / merge_authorization /
   reviewer-default / merge_conflict_resolve. If a future edit drifts those
   files (an accidental trailing newline, a reflowed sentence), agent behavior
   changes silently. Each golden below is the EXACT pre-extraction Clojure
   literal; the test asserts the loaded+tokenized file reproduces it byte for
   byte.

   The non-empty resolution assertions exercise load-framework-prompt's real
   resolver (load-prompt -> agent/load-custom-prompt). Workers run in git
   worktrees, not the repo root, so a wrong base path is a runtime crash that
   unit tests on string content alone would miss — calling the resolver here
   is the check that the files actually resolve.

   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.framework-prompt-golden-test))'"
  (:require [agentnet.worker :as worker]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private load-fw
  "Private framework-prompt loader var — the real resolver under test."
  #'worker/load-framework-prompt)

(defn- render
  [name tokens]
  (load-fw name tokens))

;; -----------------------------------------------------------------------------
;; Golden literals — copied verbatim from the pre-extraction worker.clj source.
;; -----------------------------------------------------------------------------

(def ^:private nudge-golden
  (str "You have been working for a long time without signaling completion.\n"
       "You MUST take one of these actions NOW:\n\n"
       "1. If you have meaningful changes: commit them and signal COMPLETE_AND_READY_FOR_MERGE\n"
       "2. If scope is too large: create follow-up tasks in tasks/pending/ for remaining work,\n"
       "   commit what you have (even partial notes/design docs), and signal COMPLETE_AND_READY_FOR_MERGE\n"
       "3. If you truly cannot produce a merge-ready artifact this turn, signal NEEDS_FOLLOWUP\n"
       "   and explain the remaining work. The framework will keep your claimed tasks and give you\n"
       "   one targeted follow-up prompt. This is not success.\n\n"
       "Do NOT continue working without producing a signal."))

(defn- needs-followup-golden
  "Pre-extraction body of build-needs-followup-prompt for given parts."
  [ownership-line explanation-block task-status pending-block]
  (str "## NEEDS_FOLLOWUP Follow-up\n\n"
       ownership-line
       "Continue the SAME cycle and finish a merge-ready artifact.\n"
       "Do not output NEEDS_FOLLOWUP again unless you are still blocked after this follow-up.\n"
       "Prefer the smallest useful diff. If scope is too large, create concrete follow-up tasks in the pending queue and still ship the artifact you have.\n\n"
       explanation-block
       "Task Status: " task-status "\n"
       "Remaining Pending:\n"
       pending-block
       "\n\nWhen ready, signal COMPLETE_AND_READY_FOR_MERGE."))

(defn- merge-authorization-golden
  "Pre-extraction body of build-merge-prompt."
  [project-root wt-branch]
  (str "## Merge Authorization\n\n"
       "Your work has been reviewed and approved. You are now authorized to merge to main.\n\n"
       "Steps:\n"
       "1. Commit any uncommitted changes in your worktree (git add -A && git commit -m 'wip')\n"
       "2. In the project root (" project-root "), run:\n"
       "   git checkout main && git merge " wt-branch " --no-edit\n"
       "3. If there are merge conflicts: resolve them, then git add -A && git commit --no-edit\n"
       "4. After the merge succeeds, get the commit SHA: git rev-parse --short HEAD\n"
       "5. Signal MERGE_COMPLETE(sha) — e.g. MERGE_COMPLETE(a3f7d2c)\n\n"
       "IMPORTANT: Only signal MERGE_COMPLETE after the merge is actually on main.\n"
       "If you cannot resolve conflicts after trying hard, signal NEEDS_FOLLOWUP with details."))

(defn- merge-conflict-resolve-golden
  "Pre-extraction body (after the [oompa:...] tag) of the resolver prompt."
  [error branch-log main-log diff-stat]
  (str "Your branch cannot merge into main.\n\n"
       "Error:\n" error "\n\n"
       "Your commits (not on main):\n" branch-log "\n\n"
       "New commits on main:\n" main-log "\n\n"
       "Divergence:\n" diff-stat "\n\n"
       "Make your branch cleanly mergeable into main. "
       "Preserve YOUR changes. You have full git access."))

(defn- reviewer-default-golden
  "Review-body with the DEFAULT base and the given merged/history sections.
   This is the pre-extraction literal (default base + VERDICT rubric) plus the
   R1 additions: the merged_section splice point (between the diff fence and the
   history block) and the trailing pacing/redundancy rubric line. With an empty
   merged-section and empty history this equals the original inline body for
   those parts, with the one new rubric line appended."
  [diff-content merged-section history-block]
  (str "Review the changes in this worktree.\n"
       "Focus on architecture and design, not style.\n"
       "\n\nDiff:\n```\n" diff-content "\n```\n"
       merged-section
       history-block
       "\nYour verdict MUST be on its own line, exactly one of:\n"
       "VERDICT: APPROVED\n"
       "VERDICT: NEEDS_CHANGES\n\n"
       "Pick APPROVED if the changes are correct and complete. "
       "Pick NEEDS_CHANGES if there are specific issues to fix.\n"
       "If you pick NEEDS_CHANGES, list every issue as a numbered item with "
       "the file path and what needs to change.\n"
       "Pacing: if this diff duplicates, re-litigates, or churns work already listed under \"Recently merged work on main\", pick NEEDS_CHANGES and say which merged subject it overlaps — do not approve redundant or repeated work.\n"))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(t/deftest nudge-text-is-byte-identical
  (t/is (= nudge-golden (render "nudge.md" {}))))

(t/deftest needs-followup-text-is-byte-identical
  ;; Owned tasks + explanation present.
  (let [ownership "You still own these claimed tasks: t1, t2\n\n"
        explanation "Your previous explanation:\nstill blocked on X\n\n"
        task-status "Pending: 1, In Progress: 1, Complete: 0"
        pending "- t3: do the thing"]
    (t/is (= (needs-followup-golden ownership explanation task-status pending)
             (render "needs_followup.md"
                     {:ownership_line ownership
                      :explanation_block explanation
                      :task_status task-status
                      :pending_block pending}))))
  ;; No owned tasks + no explanation + blank pending → "(none)".
  (let [ownership "You do not currently own any claimed tasks.\n\n"
        task-status "Pending: 0, In Progress: 0, Complete: 0"]
    (t/is (= (needs-followup-golden ownership "" task-status "(none)")
             (render "needs_followup.md"
                     {:ownership_line ownership
                      :explanation_block ""
                      :task_status task-status
                      :pending_block "(none)"})))))

(t/deftest merge-authorization-text-is-byte-identical
  (let [root "/repo/root" branch "oompa/sw-1-w0-i1"]
    (t/is (= (merge-authorization-golden root branch)
             (render "merge_authorization.md"
                     {:project_root root :wt_branch branch})))))

(t/deftest merge-conflict-resolve-text-is-byte-identical
  (let [error "CONFLICT in src/a.clj"
        branch-log "abc123 my commit"
        main-log "def456 their commit"
        diff-stat " 2 files changed"]
    (t/is (= (merge-conflict-resolve-golden error branch-log main-log diff-stat)
             (render "merge_conflict_resolve.md"
                     {:error error
                      :branch_log branch-log
                      :main_log main-log
                      :diff_stat diff-stat})))))

(t/deftest reviewer-default-text-is-byte-identical
  (let [default-base "Review the changes in this worktree.\nFocus on architecture and design, not style.\n"]
    ;; Default base, no merged section, history present.
    (let [diff "@@ a @@\n+line"
          history "\n## Previous Review (Round 1)\n\nThe worker has attempted fixes based on this feedback. Verify the issues below are resolved. Do NOT raise new issues.\n\nold feedback\n\n"]
      (t/is (= (reviewer-default-golden diff "" history)
               (render "reviewer.md"
                       {:base default-base
                        :diff_content diff
                        :merged_section ""
                        :history_block history}))))
    ;; Default base, no merged section, no history.
    (let [diff "@@ a @@\n+line"]
      (t/is (= (reviewer-default-golden diff "" "")
               (render "reviewer.md"
                       {:base default-base
                        :diff_content diff
                        :merged_section ""
                        :history_block ""}))))
    ;; R1: merged-section spliced between the diff fence and the history block.
    (let [diff "@@ a @@\n+line"
          merged "\n## Recently merged work on main (last 10)\n\nFix the pressure solve\nAdd shoal bathymetry\n\n"]
      (t/is (= (reviewer-default-golden diff merged "")
               (render "reviewer.md"
                       {:base default-base
                        :diff_content diff
                        :merged_section merged
                        :history_block ""}))))))

(t/deftest every-framework-file-resolves-and-reads-non-empty
  ;; Exercises the real resolver (load-prompt -> agent/load-custom-prompt) for
  ;; every extracted file. A missing/unreadable file throws ex-info; an empty
  ;; render would fail the non-blank assertion. This is the worktree base-path
  ;; tripwire that string-content tests alone cannot catch.
  (doseq [name ["nudge.md" "worker_default.md" "claim_results.md"
                "needs_followup.md" "resume.md" "reviewer.md"
                "merge_conflict_resolve.md" "merge_authorization.md"
                "fix.md" "planner_tail.md"]]
    (let [rendered (render name {})]
      (t/is (string? rendered) (str "did not load: " name))
      (t/is (not (str/blank? rendered)) (str "loaded blank: " name)))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.framework-prompt-golden-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "framework prompt golden tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
