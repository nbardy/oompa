(ns agentnet.status-transition-test
  "Regression tests for worker/status transitions.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.status-transition-test))'"
  (:require [agentnet.tasks :as tasks]
            [agentnet.worker :as worker]
            [agentnet.runs :as runs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(defn- stubbed-worker-shell
  [run-agent-fn emit-log-fn
   & {:keys [can-plan max-cycles max-resumes
             task-status pending-tasks current-count current-task-ids
             worktree-has-changes? review-loop-fn sync-fn merge-fn execute-claims-fn
             recycle-tasks-fn complete-by-ids-fn cleanup-fn salvage-fn]}]
  (with-redefs [tasks/ensure-dirs! (fn [] nil)
                tasks/pending-count (fn [] 1)
                tasks/current-count (fn [] (or current-count 0))
                tasks/current-task-ids (fn [] (or current-task-ids #{}))
                tasks/recycle-tasks! (or recycle-tasks-fn (fn [ids] (vec (sort ids))))
                tasks/complete-by-ids! (or complete-by-ids-fn (fn [ids] (vec (sort ids))))
                worker/create-iteration-worktree! (fn [_ _ _ _]
                                                   {:dir ".wt" :branch "oompa/w0" :path "/tmp/wt"})
                worker/cleanup-worktree! (or cleanup-fn (fn [& _] nil))
                worker/preserve-merge-failure-salvage! (or salvage-fn (fn [& _] nil))
                worker/backoff-sleep! (fn [& _] nil)
                worker/build-context (fn []
                                       {:task_status (or task-status "Pending: 1, In Progress: 0, Complete: 0")
                                        :pending_tasks (or pending-tasks "- task-001: Build thing")})
                worker/run-agent! run-agent-fn
                worker/execute-claims! (or execute-claims-fn
                                            (fn [& _]
                                              {:claimed ["task-001"]
                                               :failed []
                                               :resume-prompt "## Claim Results"}))
                worker/worktree-has-changes? (if (fn? worktree-has-changes?)
                                               worktree-has-changes?
                                               (fn [_] (boolean worktree-has-changes?)))
                worker/auto-mergeable-diff? (fn [& _] false)
                worker/review-loop! (or review-loop-fn (fn [& _] {:approved? true :attempts 0}))
                worker/sync-worktree-to-main! (or sync-fn (fn [& _] :ok))
                worker/merge-to-main! (or merge-fn (fn [& _] {:ok? true :completed-count 1}))
                worker/run-merge-agent! (or merge-fn (fn [& _] {:ok? true :sha "abc123"}))
                worker/recover-merge-failure! (fn [& _] {:ok? false :completed-count 0})
                worker/complete-merge! (fn [& _] nil)
                worker/emit-cycle-log! emit-log-fn]
    (worker/run-worker! {:id "w0"
                         :harness :codex
                         :model "gpt-5"
                         :max-cycles (or max-cycles 1)
                         :can-plan (if (nil? can-plan) true can-plan)
                         :max-resumes (or max-resumes 7)})))

(defn- capture-log!
  [logs]
  (fn [& xs]
    (swap! logs conj (last xs))))

(t/deftest claim-signal-transitions-to-claimed-cycle
  (let [logs (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-1"}
                     {:output "COMPLETE_AND_READY_FOR_MERGE"
                      :exit 0
                      :done? false
                      :merge? true
                      :claim-ids nil
                      :session-id "sid-1"}))
                 (capture-log! logs))]
    (t/is (= :completed (:status result)))
    (t/is (= 1 (:claims result)))
    (t/is (= 2 (count @logs)))
    (t/is (= :claimed (:outcome (first @logs))))
    (t/is (= ["task-001"] (:claimed-task-ids (first @logs))))))

(t/deftest unsuccessful-claim-ends-cycle-without-resume
  (let [logs (atom [])
        prompts-seen (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [_ _ _ _ _ & {:keys [resume-prompt-override]}]
                   (swap! call-count inc)
                   (swap! prompts-seen conj resume-prompt-override)
                   {:output "CLAIM(task-wrong-role)"
                    :exit 0
                    :done? false
                    :merge? false
                    :claim-ids ["task-wrong-role"]
                    :session-id "sid-no-claim"})
                 (capture-log! logs)
                 :max-cycles 1
                 :execute-claims-fn (fn [& _]
                                      {:claimed []
                                       :failed ["task-wrong-role"]
                                       :resume-prompt "## Claim Results"}))]
    (t/is (= :completed (:status result)))
    (t/is (= 1 @call-count))
    (t/is (= [nil] @prompts-seen))
    (t/is (= [:no-claim] (mapv :outcome @logs)))
    (t/is (= [] (:claimed-task-ids (first @logs))))))

(t/deftest executor-done-signal-stops-worker-as-error
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "__DONE__"
                    :exit 0
                    :done? true
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-2"})
                 (capture-log! logs)
                 :max-cycles 3)]
    (t/is (= :error (:status result)))
    (t/is (= 1 (count @logs)))
    (t/is (= :error (:outcome (first @logs))))
    (t/is (re-find #"__DONE__ is not a valid executor signal"
                   (or (:error-snippet (first @logs)) "")))))

(t/deftest needs-followup-resumes-same-cycle-and-keeps-claims
  (let [logs (atom [])
        prompts-seen (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [_ _ _ _ _ & {:keys [resume-prompt-override]}]
                   (swap! call-count inc)
                   (swap! prompts-seen conj resume-prompt-override)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-followup"}
                     2 {:output "NEEDS_FOLLOWUP\n\nNeed one sharper pass to finish the merge-ready diff."
                        :exit 0
                        :done? false
                        :merge? false
                        :needs-followup? true
                        :claim-ids nil
                        :session-id "sid-followup"}
                     {:output "COMPLETE_AND_READY_FOR_MERGE"
                      :exit 0
                      :done? false
                      :merge? true
                      :claim-ids nil
                      :session-id "sid-followup"}))
                 (capture-log! logs)
                 :max-cycles 1
                 :worktree-has-changes? true)]
    (t/is (= :completed (:status result)))
    (t/is (= [:claimed :needs-followup :merged] (mapv :outcome @logs)))
    (t/is (= ["task-001"] (:claimed-task-ids (last @logs))))
    (t/is (nil? (nth @prompts-seen 0)))
    (t/is (re-find #"Claim Results" (nth @prompts-seen 1)))
    (t/is (string? (nth @prompts-seen 2)))
    (t/is (re-find #"NEEDS_FOLLOWUP Follow-up" (nth @prompts-seen 2)))))

(t/deftest shared-current-diff-does-not-create-foreign-claims
  (with-redefs [tasks/current-task-ids (fn [] #{"task-001" "task-foreign"})]
    (t/is (= #{} (#'worker/detect-claimed-tasks #{"task-001"})))))

(t/deftest role-hinted-tasks-reject-wrong-worker-role
  (let [worker {:role "chief_scientist_cto" :can-claim-gpu false}
        task {:id "wave-gpu"
              :role_hint "engineer_source_of_truth_gpu"
              :gpu_heavy true}
        denial (#'worker/task-claim-denial worker task)]
    (t/is (= "role-mismatch" (:reason denial)))
    (t/is (= "chief_scientist_cto" (:worker-role denial)))
    (t/is (= "engineer_source_of_truth_gpu" (:task-role-hint denial)))))

(t/deftest gpu-heavy-tasks-require-explicit-gpu-claim-capability
  (t/is (= "gpu-restricted"
           (:reason (#'worker/task-claim-denial
                      {:role "engineer_renderer_visuals" :can-claim-gpu false}
                      {:id "wave-gpu"
                       :role_hint "engineer_renderer_visuals"
                       :gpu_heavy true}))))
  (t/is (nil? (#'worker/task-claim-denial
                {:role "engineer_source_of_truth_gpu" :can-claim-gpu true}
                {:id "wave-gpu"
                 :role_hint "engineer_source_of_truth_gpu"
                 :gpu_heavy true}))))

(t/deftest task-dependencies-must-be-complete-before-claim
  (with-redefs [tasks/list-complete (fn [] [{:id "wave-004-root-projection-audit-shape"}])]
    (let [denial (#'worker/task-claim-denial
                   {:role "engineer_source_of_truth_gpu" :can-claim-gpu true}
                   {:id "wave-gpu"
                    :role_hint "engineer_source_of_truth_gpu"
                    :gpu_heavy true
                    :depends_on ["wave-004-root-projection-audit-shape"
                                 "wave-005-hard-row-source-signatures"]})]
      (t/is (= "dependency-missing" (:reason denial)))
      (t/is (= "wave-005-hard-row-source-signatures" (:missing-dependencies denial)))))
  (with-redefs [tasks/list-complete (fn [] [{:id "wave-004-root-projection-audit-shape"}
                                            {:id "wave-005-hard-row-source-signatures"}])]
    (t/is (nil? (#'worker/task-claim-denial
                  {:role "engineer_source_of_truth_gpu" :can-claim-gpu true}
                  {:id "wave-gpu"
                   :role_hint "engineer_source_of_truth_gpu"
                   :gpu_heavy true
                   :depends_on ["wave-004-root-projection-audit-shape"
                                "wave-005-hard-row-source-signatures"]})))))

(t/deftest merge-with-changes-requires-claimed-task
  (let [logs (atom [])
        merge-called? (atom false)
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "COMPLETE_AND_READY_FOR_MERGE"
                    :exit 0
                    :done? false
                    :merge? true
                    :claim-ids nil
                    :session-id "sid-unclaimed-merge"})
                 (capture-log! logs)
                 :max-cycles 3
                 :worktree-has-changes? true
                 :merge-fn (fn [& _]
                             (reset! merge-called? true)
                             {:ok? true :sha "bad"}))]
    (t/is (= :error (:status result)))
    (t/is (false? @merge-called?))
    (t/is (= :error (:outcome (last @logs))))
    (t/is (re-find #"no claimed tasks" (or (:error-snippet (last @logs)) "")))))

(t/deftest terminal-no-changes-completes-claims-from-earlier-attempt
  (let [logs (atom [])
        completed (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-no-changes"}
                     {:output "COMPLETE_AND_READY_FOR_MERGE"
                      :exit 0
                      :done? false
                      :merge? true
                      :claim-ids nil
                      :session-id "sid-no-changes"}))
                 (capture-log! logs)
                 :max-cycles 1
                 :worktree-has-changes? false
                 :complete-by-ids-fn (fn [ids]
                                        (let [ids (vec (sort ids))]
                                          (swap! completed conj ids)
                                          ids)))]
    (t/is (= :completed (:status result)))
    (t/is (= [["task-001"]] @completed))
    (t/is (= :no-changes (:outcome (last @logs))))
    (t/is (= ["task-001"] (:claimed-task-ids (last @logs))))))

(t/deftest no-diff-merge-without-claims-stops-worker
  (let [logs (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   {:output "COMPLETE_AND_READY_FOR_MERGE"
                    :exit 0
                    :done? false
                    :merge? true
                    :claim-ids nil
                    :session-id "sid-idle-merge"})
                 (capture-log! logs)
                 :max-cycles 3
                 :worktree-has-changes? false)]
    (t/is (= :completed (:status result)))
    (t/is (= 1 @call-count))
    (t/is (= :no-claim (:outcome (last @logs))))
    (t/is (= [] (:claimed-task-ids (last @logs))))))

(t/deftest repeated-needs-followup-consumes-resume-budget-and-recycles
  ;; K1: NEEDS_FOLLOWUP no longer has its own budget. Each continuation counts
  ;; against the single per-cycle attempt budget (max-resumes). When that budget
  ;; is spent, the resume-cap guard recycles the claims and moves to the next
  ;; cycle. With max-resumes 2: attempt 1 = CLAIM, attempt 2 = NEEDS_FOLLOWUP
  ;; (continues), attempt 3 exceeds the cap → recycle. The agent is never asked a
  ;; 4th time, so it emits at most three NEEDS_FOLLOWUP logs before the cap.
  (let [logs (atom [])
        recycled (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-followup-limit"}
                     {:output "NEEDS_FOLLOWUP\n\nStill blocked."
                      :exit 0
                      :done? false
                      :merge? false
                      :needs-followup? true
                      :claim-ids nil
                      :session-id "sid-followup-limit"}))
                 (capture-log! logs)
                 :max-cycles 1
                 :max-resumes 2
                 :recycle-tasks-fn (fn [ids]
                                     (let [ids (vec (sort ids))]
                                       (swap! recycled conj ids)
                                       ids)))]
    ;; Cycle 1 budget is spent; completed→1 == max-cycles → finish :completed.
    (t/is (= :completed (:status result)))
    ;; The resume-cap guard recycled the still-owned claim.
    (t/is (= [["task-001"]] @recycled))
    ;; The last recorded attempt before the cap was a NEEDS_FOLLOWUP continuation.
    (t/is (= :needs-followup (:outcome (last @logs))))
    ;; Agent was asked exactly twice: CLAIM then one NEEDS_FOLLOWUP turn.
    (t/is (= 2 @call-count))))

(t/deftest cycle-schema-includes-claimed-outcome
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "claimed"))))

(t/deftest cycle-schema-includes-no-claim-outcome
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "no-claim"))))

(t/deftest cycle-schema-covers-merge-sync-failed-outcomes
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "sync-failed"))
    (t/is (contains? outcomes "merge-failed"))
    (t/is (contains? outcomes "interrupted"))))

(t/deftest cycle-schema-includes-stuck-outcome
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "stuck"))))

(t/deftest cycle-schema-includes-needs-followup-outcome
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "needs-followup"))))

(t/deftest working-resumes-bounded-by-resume-cap-without-stuck-outcome
  ;; K1: the separate max-working-resumes counter and its :stuck outcome are
  ;; gone. A worker that keeps "working" without a signal is resumed until the
  ;; single per-cycle attempt budget (max-resumes) is spent, at which point the
  ;; resume-cap guard recycles claims and moves on WITHOUT emitting a log. With
  ;; max-resumes 2 the worker runs attempt 1 and attempt 2 (both :working), then
  ;; attempt 3 exceeds the cap.
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "still thinking..."
                    :exit 0
                    :done? false
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-working"})
                 (capture-log! logs)
                 :max-cycles 1
                 :max-resumes 2)]
    (t/is (= :completed (:status result)))
    (let [outcomes (mapv :outcome @logs)]
      (t/is (= [:working :working] outcomes))
      (t/is (not (some #{:stuck} outcomes))))))

(t/deftest nudge-prompt-injected-on-penultimate-resume-attempt
  ;; K1: the wrap-up nudge is folded into max-resumes and fires exactly once, on
  ;; the penultimate attempt (one attempt left, attempt == max-resumes - 1). With
  ;; max-resumes 3 the agent is called on attempts 1, 2, 3; the nudge is injected
  ;; into the attempt-3 prompt because attempt 2 is the penultimate one.
  (let [prompts-seen (atom [])
        call-count (atom 0)]
    (stubbed-worker-shell
      (fn [_ _ _ _ _ & {:keys [resume-prompt-override]}]
        (swap! call-count inc)
        (swap! prompts-seen conj resume-prompt-override)
        {:output "still working"
         :exit 0
         :done? false
         :merge? false
         :claim-ids nil
         :session-id "sid-nudge"})
      (fn [& _] nil)
      :max-cycles 1
      :max-resumes 3)
    (t/is (= 3 @call-count))
    ;; attempt 1 prompt: none; attempt 2 prompt: none (not yet penultimate);
    ;; attempt 3 prompt: the nudge (injected when attempt 2 was penultimate).
    (t/is (nil? (nth @prompts-seen 0)))
    (t/is (nil? (nth @prompts-seen 1)))
    (t/is (string? (nth @prompts-seen 2)))
    (t/is (re-find #"without signaling completion" (nth @prompts-seen 2)))))

;; -----------------------------------------------------------------------------
;; DEFECT 1 regression (audit runs 80a33337/9f004a39): merge signaled with
;; changes but no claimed tasks was FATAL on the first occurrence — it killed
;; 32 of 36 worker-lifetimes (11.8h = 32% of all worker time). The worker must
;; recycle the cycle (leaving the worktree for salvage) and only fall through
;; to the old fatal stop on the third occurrence.
;; -----------------------------------------------------------------------------

(t/deftest merge-no-claim-recycles-cycle-and-only-third-occurrence-is-fatal
  (let [logs (atom [])
        cleanup-calls (atom 0)
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   {:output "COMPLETE_AND_READY_FOR_MERGE"
                    :exit 0
                    :done? false
                    :merge? true
                    :claim-ids nil
                    :session-id "sid-mnc"})
                 (capture-log! logs)
                 :max-cycles 10
                 :worktree-has-changes? true
                 :cleanup-fn (fn [& _] (swap! cleanup-calls inc)))]
    ;; Occurrences 1 and 2 recycle the cycle; occurrence 3 is fatal so a
    ;; pathological agent cannot loop forever.
    (t/is (= :error (:status result)))
    (t/is (= 3 @call-count))
    (t/is (= [:error :error :error] (mapv :outcome @logs)))
    ;; The worktree is left for salvage on every occurrence — never cleaned up.
    (t/is (= 0 @cleanup-calls))))

(t/deftest merge-no-claim-under-cap-does-not-kill-worker
  ;; With max-cycles below the occurrence cap the worker survives its full
  ;; budget instead of dying on the first protocol error.
  (let [logs (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   {:output "COMPLETE_AND_READY_FOR_MERGE"
                    :exit 0
                    :done? false
                    :merge? true
                    :claim-ids nil
                    :session-id "sid-mnc2"})
                 (capture-log! logs)
                 :max-cycles 2
                 :worktree-has-changes? true)]
    (t/is (= :completed (:status result)))
    (t/is (= 2 @call-count))
    (t/is (= [:error :error] (mapv :outcome @logs)))))

;; -----------------------------------------------------------------------------
;; DEFECT 2 regression (audit run 9f004a39, w7-c2 lost-work incident): worker
;; w7's round-2 APPROVED deliverable (2.5h of work) was destroyed when merge
;; failed and cleanup-worktree! ran unconditionally. On merge failure the
;; worktree must survive and a salvage ref must be created; cleanup happens
;; only on merge success.
;; -----------------------------------------------------------------------------

(t/deftest merge-failure-preserves-worktree-and-creates-salvage-ref
  (let [logs (atom [])
        cleanup-calls (atom [])
        salvage-calls (atom [])
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-mf"}
                     {:output "COMPLETE_AND_READY_FOR_MERGE"
                      :exit 0
                      :done? false
                      :merge? true
                      :claim-ids nil
                      :session-id "sid-mf"}))
                 (capture-log! logs)
                 :max-cycles 1
                 :worktree-has-changes? true
                 :merge-fn (fn [& _] {:ok? false :sha nil})
                 :cleanup-fn (fn [& args] (swap! cleanup-calls conj args))
                 :salvage-fn (fn [_project-root _swarm-id worker-id cycle wt-state]
                               (swap! salvage-calls conj {:worker-id worker-id
                                                          :cycle cycle
                                                          :wt-state wt-state})))]
    (t/is (= :completed (:status result)))
    (t/is (= :merge-failed (:outcome (last @logs))))
    ;; Claims are recycled, not destroyed with the work.
    (t/is (= ["task-001"] (vec (:recycled-tasks (last @logs)))))
    ;; The worktree survives; salvage-ref creation was invoked exactly once.
    (t/is (= [] @cleanup-calls))
    (t/is (= 1 (count @salvage-calls)))
    (t/is (= "w0" (:worker-id (first @salvage-calls))))
    (t/is (= 1 (:cycle (first @salvage-calls))))))

(t/deftest merge-success-cleans-up-worktree-without-salvage-ref
  ;; Inverse guard: a successful merge must still clean up and must NOT create
  ;; salvage refs (otherwise refs would accumulate on every healthy cycle).
  (let [cleanup-calls (atom 0)
        salvage-calls (atom 0)
        call-count (atom 0)
        result (stubbed-worker-shell
                 (fn [& _]
                   (swap! call-count inc)
                   (case @call-count
                     1 {:output "CLAIM(task-001)"
                        :exit 0
                        :done? false
                        :merge? false
                        :claim-ids ["task-001"]
                        :session-id "sid-ms"}
                     {:output "COMPLETE_AND_READY_FOR_MERGE"
                      :exit 0
                      :done? false
                      :merge? true
                      :claim-ids nil
                      :session-id "sid-ms"}))
                 (fn [& _] nil)
                 :max-cycles 1
                 :worktree-has-changes? true
                 :cleanup-fn (fn [& _] (swap! cleanup-calls inc))
                 :salvage-fn (fn [& _] (swap! salvage-calls inc)))]
    (t/is (= :completed (:status result)))
    (t/is (= 1 @cleanup-calls))
    (t/is (= 0 @salvage-calls))))

(t/deftest preserve-salvage-ref-creates-git-ref-at-branch-head
  ;; Real git fixture: the salvage helper must pin the worktree branch head
  ;; under refs/heads/salvage/... so later stale-branch cleanup
  ;; (create-iteration-worktree! force-deletes oompa/* branches on restart)
  ;; cannot destroy approved work (audit run 9f004a39 w7-c2).
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "oompa-salvage-test"
                       (into-array java.nio.file.attribute.FileAttribute [])))
        root (.getAbsolutePath dir)
        sh! (fn [& args]
              (let [r (process/sh (vec args) {:dir root :out :string :err :string})]
                (assert (zero? (:exit r)) (str (vec args) " → " (:err r)))
                r))]
    (try
      (sh! "git" "init" "-q")
      (sh! "git" "-c" "user.email=t@t" "-c" "user.name=t"
           "commit" "--allow-empty" "-m" "base")
      (sh! "git" "branch" "oompa/s9f004a39-w7-i2")
      (#'worker/preserve-merge-failure-salvage!
        root "9f004a39" "w7" 2
        {:branch "oompa/s9f004a39-w7-i2" :path (str root "/.ws9f004a39-w7-i2")})
      (let [salvage (process/sh ["git" "rev-parse" "refs/heads/salvage/9f004a39-w7-c2"]
                                {:dir root :out :string :err :string})
            branch (process/sh ["git" "rev-parse" "oompa/s9f004a39-w7-i2"]
                               {:dir root :out :string :err :string})]
        (t/is (zero? (:exit salvage)))
        (t/is (= (:out branch) (:out salvage))))
      (finally
        (run! #(.delete %) (reverse (file-seq dir)))))))

;; -----------------------------------------------------------------------------
;; DEFECT 3 regression (audit runs 80a33337/9f004a39): two swarms overlapped
;; 2h21m on one queue — stale-base diffs (18/22 round-1 rejections),
;; double-claims, a 68-minute duplicate implementation. Startup must refuse
;; when a prior run's started.json has no sibling stopped.json and its
;; recorded pid is still alive.
;; -----------------------------------------------------------------------------

(t/deftest single-swarm-lock-refuses-when-prior-swarm-is-alive
  (with-redefs [runs/list-runs (fn [] ["run-live" "run-stopped" "run-dead"])
                runs/read-started (fn [rid] (get {"run-live" {:swarm-id "run-live" :pid 12345}
                                                  "run-stopped" {:swarm-id "run-stopped" :pid 22222}
                                                  "run-dead" {:swarm-id "run-dead" :pid 33333}}
                                                 rid))
                runs/read-stopped (fn [rid] (when (= rid "run-stopped") {:reason "completed"}))
                worker/swarm-pid-alive? (fn [pid] (= 12345 pid))]
    (let [ex (try (worker/ensure-single-swarm! false) nil (catch Exception e e))]
      (t/is (some? ex))
      ;; Refusal names the live run id and pid so the operator can kill it.
      (t/is (re-find #"run-live" (.getMessage ex)))
      (t/is (re-find #"12345" (.getMessage ex)))
      ;; Stopped and dead-pid runs alone must NOT trigger a refusal.
      (with-redefs [runs/list-runs (fn [] ["run-stopped" "run-dead"])]
        (t/is (nil? (worker/ensure-single-swarm! false)))))))

(t/deftest single-swarm-lock-force-bypasses-refusal
  (with-redefs [runs/list-runs (fn [] ["run-live"])
                runs/read-started (fn [_] {:swarm-id "run-live" :pid 12345})
                runs/read-stopped (fn [_] nil)
                worker/swarm-pid-alive? (fn [_] true)]
    (t/is (nil? (worker/ensure-single-swarm! true)))))

(t/deftest single-swarm-lock-ignores-own-run-entry
  ;; cmd-swarm writes started.json BEFORE run-workers! re-checks the lock, so
  ;; the current process's own entry (alive by definition) must be excluded or
  ;; every swarm would refuse itself.
  (let [self-pid (.pid (java.lang.ProcessHandle/current))]
    (with-redefs [runs/list-runs (fn [] ["run-self"])
                  runs/read-started (fn [_] {:swarm-id "run-self" :pid self-pid})
                  runs/read-stopped (fn [_] nil)
                  worker/swarm-pid-alive? (fn [_] true)]
      (t/is (nil? (worker/ensure-single-swarm! false))))))

;; -----------------------------------------------------------------------------
;; DEFECT 4 regression (audit runs 80a33337/9f004a39): stopped.json recorded
;; reason "completed", error nil, although every worker terminated in an error
;; state and pending tasks remained — nothing alerted for ~20h.
;; -----------------------------------------------------------------------------

(t/deftest stopped-reason-is-workers-exhausted-when-all-workers-died-and-tasks-remain
  ;; The exact audit shape: zero :completed workers, pending tasks remain.
  (t/is (= :workers-exhausted
           (#'worker/stopped-reason [{:status :error} {:status :fatal-error}] 5)))
  ;; Any worker reaching :completed keeps the reason "completed".
  (t/is (= :completed
           (#'worker/stopped-reason [{:status :error} {:status :completed}] 5)))
  ;; A genuinely drained queue is "completed" even if workers errored out.
  (t/is (= :completed
           (#'worker/stopped-reason [{:status :error}] 0))))

(t/deftest stopped-schema-covers-workers-exhausted-and-worker-outcomes
  ;; write-stopped! now emits :worker-outcomes/:pending-count and the
  ;; "workers-exhausted" reason; the schema declares additionalProperties
  ;; false, so a writer/schema drift silently invalidates every stopped.json.
  (let [schema (json/parse-string (slurp (io/file "schemas/stopped.schema.json")) true)]
    (t/is (contains? (set (get-in schema [:properties :reason :enum])) "workers-exhausted"))
    (t/is (contains? (set (keys (:properties schema))) :worker-outcomes))
    (t/is (contains? (set (keys (:properties schema))) :pending-count))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.status-transition-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "status transition tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
