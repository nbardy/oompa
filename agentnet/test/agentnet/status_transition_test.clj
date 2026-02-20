(ns agentnet.status-transition-test
  "Regression tests for worker/status transitions.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.status-transition-test))'"
  (:require [agentnet.tasks :as tasks]
            [agentnet.worker :as worker]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(defn- stubbed-worker-shell [run-agent-fn emit-log-fn & {:keys [can-plan iterations recycle-fn]}]
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
    (worker/run-worker! {:id "w0"
                         :harness :codex
                         :model "gpt-5"
                         :iterations (or iterations 1)
                         :can-plan (if (nil? can-plan) true can-plan)})))

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

(t/deftest planner-done-signal-transitions-to-done
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
    (t/is (= :done (:status result)))
    (t/is (= 1 (count @logs)))
    (t/is (= :done (:outcome (first @logs))))))

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

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.status-transition-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "status transition tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
