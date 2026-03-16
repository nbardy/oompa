(ns agentnet.status-transition-test
  "Regression tests for worker/status transitions.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.status-transition-test))'"
  (:require [agentnet.tasks :as tasks]
            [agentnet.worker :as worker]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(defn- stubbed-worker-shell
  [run-agent-fn emit-log-fn
   & {:keys [can-plan max-cycles max-working-resumes max-needs-followups
             task-status pending-tasks current-count current-task-ids
             worktree-has-changes? review-loop-fn sync-fn merge-fn
             recycle-tasks-fn]}]
  (with-redefs [tasks/ensure-dirs! (fn [] nil)
                tasks/pending-count (fn [] 1)
                tasks/current-count (fn [] (or current-count 0))
                tasks/current-task-ids (fn [] (or current-task-ids #{}))
                tasks/recycle-tasks! (or recycle-tasks-fn (fn [ids] (vec (sort ids))))
                worker/create-iteration-worktree! (fn [_ _ _ _]
                                                   {:dir ".wt" :branch "oompa/w0" :path "/tmp/wt"})
                worker/cleanup-worktree! (fn [& _] nil)
                worker/backoff-sleep! (fn [& _] nil)
                worker/build-context (fn []
                                       {:task_status (or task-status "Pending: 1, In Progress: 0, Complete: 0")
                                        :pending_tasks (or pending-tasks "- task-001: Build thing")})
                worker/run-agent! run-agent-fn
                worker/execute-claims! (fn [_]
                                         {:claimed ["task-001"]
                                          :failed []
                                          :resume-prompt "## Claim Results"})
                worker/worktree-has-changes? (if (fn? worktree-has-changes?)
                                               worktree-has-changes?
                                               (fn [_] (boolean worktree-has-changes?)))
                worker/task-only-diff? (fn [_] false)
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
                         :max-working-resumes (or max-working-resumes 5)
                         :max-needs-followups (or max-needs-followups 1)})))

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

(t/deftest terminal-no-changes-recycles-claims-from-earlier-attempt
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
                 :recycle-tasks-fn (fn [ids]
                                     (let [ids (vec (sort ids))]
                                       (swap! recycled conj ids)
                                       ids)))]
    (t/is (= :completed (:status result)))
    (t/is (= [["task-001"]] @recycled))
    (t/is (= :no-changes (:outcome (last @logs))))
    (t/is (= ["task-001"] (:recycled-tasks (last @logs))))))

(t/deftest exhausted-needs-followup-recycles-claims-and-stops
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
                     2 {:output "NEEDS_FOLLOWUP\n\nFirst follow-up."
                        :exit 0
                        :done? false
                        :merge? false
                        :needs-followup? true
                        :claim-ids nil
                        :session-id "sid-followup-limit"}
                     {:output "NEEDS_FOLLOWUP\n\nStill blocked."
                      :exit 0
                      :done? false
                      :merge? false
                      :needs-followup? true
                      :claim-ids nil
                      :session-id "sid-followup-limit"}))
                 (capture-log! logs)
                 :max-cycles 3
                 :max-needs-followups 1
                 :recycle-tasks-fn (fn [ids]
                                     (let [ids (vec (sort ids))]
                                       (swap! recycled conj ids)
                                       ids)))]
    (t/is (= :error (:status result)))
    (t/is (= [["task-001"]] @recycled))
    (t/is (= :needs-followup (:outcome (last @logs))))))

(t/deftest cycle-schema-includes-claimed-outcome
  (let [schema (json/parse-string (slurp (io/file "schemas/cycle.schema.json")) true)
        outcomes (set (get-in schema [:properties :outcome :enum]))]
    (t/is (contains? outcomes "claimed"))))

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

(t/deftest working-resumes-emits-stuck-after-max
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "still thinking..."
                    :exit 0
                    :done? false
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-stuck"})
                 (capture-log! logs)
                 :max-cycles 1
                 :max-working-resumes 2)]
    (t/is (= :completed (:status result)))
    (let [outcomes (mapv :outcome @logs)]
      (t/is (= 3 (count outcomes)))
      (t/is (= :working (nth outcomes 0)))
      (t/is (= :working (nth outcomes 1)))
      (t/is (= :stuck (nth outcomes 2))))))

(t/deftest nudge-prompt-injected-at-max-working-resumes
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
      :max-working-resumes 2)
    (t/is (= 3 @call-count))
    (t/is (nil? (nth @prompts-seen 0)))
    (t/is (nil? (nth @prompts-seen 1)))
    (t/is (string? (nth @prompts-seen 2)))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.status-transition-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "status transition tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
