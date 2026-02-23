(ns agentnet.status-transition-test
  "Regression tests for worker/status transitions.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.status-transition-test))'"
  (:require [agentnet.tasks :as tasks]
            [agentnet.worker :as worker]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(defn- stubbed-worker-shell [run-agent-fn emit-log-fn & {:keys [can-plan iterations recycle-fn max-working-resumes]}]
  (with-redefs [tasks/ensure-dirs! (fn [] nil)
                tasks/pending-count (fn [] 1)
                tasks/current-count (fn [] 0)
                tasks/current-task-ids (fn [] #{})
                worker/create-iteration-worktree! (fn [_ _ _] {:dir ".wt" :branch "oompa/w0" :path "/tmp/wt"})
                worker/cleanup-worktree! (fn [& _] nil)
                worker/build-context (fn [] {:task_status "Pending: 1, In Progress: 0, Complete: 0"
                                             :pending_tasks "- task-001: Build thing"})
                worker/run-agent! run-agent-fn
                worker/execute-claims! (fn [_]
                                         {:claimed ["task-001"]
                                          :failed []
                                          :resume-prompt "## Claim Results"})
                worker/recycle-orphaned-tasks! (or recycle-fn (fn [& _] 0))
                worker/emit-cycle-log! emit-log-fn]
    (worker/run-worker! (cond-> {:id "w0"
                                  :harness :codex
                                  :model "gpt-5"
                                  :iterations (or iterations 1)
                                  :can-plan (if (nil? can-plan) true can-plan)
                                  :max-working-resumes (or max-working-resumes 5)}
                          true identity))))

(t/deftest claim-signal-transitions-to-claimed-cycle
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "CLAIM(task-001)"
                    :exit 0
                    :done? false
                    :merge? false
                    :claim-ids ["task-001"]
                    :session-id "sid-1"})
                 (fn [_ _ _ _ _ data]
                   (swap! logs conj data)))]
    (t/is (= :exhausted (:status result)))
    (t/is (= 1 (:claims result)))
    (t/is (= 1 (count @logs)))
    (t/is (= :claimed (:outcome (first @logs))))
    (t/is (= ["task-001"] (:claimed-task-ids (first @logs))))))

(t/deftest planner-done-signal-resets-like-executor
  ;; After __DONE__ unification, planners and executors behave identically:
  ;; each __DONE__ resets the session and continues to the next iteration.
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "__DONE__"
                    :exit 0
                    :done? true
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-2"})
                 (fn [_ _ _ _ _ data]
                   (swap! logs conj data))
                 :can-plan true
                 :iterations 3)]
    (t/is (= :exhausted (:status result)))
    (t/is (= 3 (count @logs)))
    (t/is (every? #(= :executor-done (:outcome %)) @logs))))

(t/deftest executor-done-signal-transitions-to-executor-done-cycle
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "__DONE__"
                    :exit 0
                    :done? true
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-3"})
                 (fn [_ _ _ _ _ data]
                   (swap! logs conj data))
                 :can-plan false
                 :iterations 1
                 :recycle-fn (fn [& _] 0))]
    (t/is (= :exhausted (:status result)))
    (t/is (= 1 (count @logs)))
    (t/is (= :executor-done (:outcome (first @logs))))))

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

(t/deftest working-resumes-emits-stuck-after-max
  ;; With max-working-resumes=2, the worker should:
  ;; iter 1: working (wr=1, under limit)
  ;; iter 2: working (wr=2, at limit → nudge injected for next resume)
  ;; iter 3: working (wr=3, > limit → stuck, session reset)
  ;; iter 4: exhausted (iterations=4, fresh start, working again wr=1)
  (let [logs (atom [])
        result (stubbed-worker-shell
                 (fn [& _]
                   {:output "still thinking..."
                    :exit 0
                    :done? false
                    :merge? false
                    :claim-ids nil
                    :session-id "sid-stuck"})
                 (fn [_ _ _ _ _ data]
                   (swap! logs conj data))
                 :iterations 4
                 :max-working-resumes 2)]
    (t/is (= :exhausted (:status result)))
    (let [outcomes (mapv :outcome @logs)]
      ;; 3 working + 1 stuck = 4 logs (iter 4 is working again after reset)
      (t/is (= 4 (count outcomes)))
      (t/is (= :working (nth outcomes 0)))  ;; wr=1
      (t/is (= :working (nth outcomes 1)))  ;; wr=2, nudge queued
      (t/is (= :stuck   (nth outcomes 2)))  ;; wr=3 > max, killed
      (t/is (= :working (nth outcomes 3))))))  ;; fresh session, wr=1

(t/deftest nudge-prompt-injected-at-max-working-resumes
  ;; Verify the nudge prompt is passed as resume-prompt-override when wr hits max.
  ;; At wr=max, the nudge is queued for the NEXT run-agent! call.
  (let [prompts-seen (atom [])
        call-count (atom 0)]
    (stubbed-worker-shell
      (fn [worker wt-path context session-id resume? & {:keys [resume-prompt-override]}]
        (swap! call-count inc)
        (swap! prompts-seen conj resume-prompt-override)
        {:output "still working"
         :exit 0
         :done? false
         :merge? false
         :claim-ids nil
         :session-id "sid-nudge"})
      (fn [& _] nil)
      :iterations 4
      :max-working-resumes 2)
    ;; Call 1 (iter 1): fresh start, no override
    ;; Call 2 (iter 2): resume, wr=1, no nudge yet
    ;; Call 3 (iter 3): resume, wr=2 (at limit), nudge injected
    ;; iter 3 returns working → wr=3 > max → stuck → session reset
    ;; Call 4 (iter 4): fresh start again, no override
    (t/is (= 4 @call-count))
    (t/is (nil? (nth @prompts-seen 0)))      ;; fresh start
    (t/is (nil? (nth @prompts-seen 1)))      ;; wr=1, under limit
    (t/is (string? (nth @prompts-seen 2)))   ;; wr=2, nudge injected
    (t/is (nil? (nth @prompts-seen 3)))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.status-transition-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "status transition tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
