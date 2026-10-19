(ns agentnet.buddy-prompt-test
  (:require [agentnet.tasks :as tasks]
            [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [clojure.string :as str]
            [clojure.test :as t]))

(t/deftest buddy-worker-receives-canonical-criteria-without-file-queue
  (let [seen (atom nil)
        task {:id "task-real" :summary "Reopen saved reef"
              :doneCriteria "Cold browser reopen retains actual saved water"
              :ownerId "buddy-product" :revision 8}]
    (with-redefs [tasks/buddies? (constantly true)
                  tasks/list-pending (constantly [task])
                  tasks/list-current (constantly [])
                  tasks/list-complete (constantly [])
                  harness/make-session-id (constantly "test-session")
                  harness/run-command! (fn [_ opts] (reset! seen (:prompt opts))
                                         {:exit 0 :out "CLAIM(task-real)" :err ""})
                  harness/parse-output (fn [_ out sid] {:output out :session-id sid})]
      (#'worker/run-agent! {:id "w0" :swarm-id "test" :harness :codex}
                          "/tmp/external-cambium-worktree"
                          (#'worker/build-context) nil false)
      (t/is (str/includes? @seen "Cold browser reopen retains actual saved water"))
      (t/is (str/includes? @seen "buddy-product"))
      (t/is (str/includes? @seen "does not establish"))
      (t/is (not (str/includes? @seen "tasks/pending/")))
      (t/is (not (str/includes? @seen "../tasks"))))))

(t/deftest buddy-claim-results-include-full-task-not-only-title
  (let [task {:id "task-real" :summary "Reef" :doneCriteria "Exact result bytes"}]
    (with-redefs [tasks/buddies? (constantly true)
                  tasks/claim-by-ids! (fn [_ _] [{:id "task-real" :status :claimed}])
                  tasks/list-pending (constantly [])
                  tasks/list-current (constantly [task])
                  tasks/list-complete (constantly [])]
      (t/is (str/includes? (:resume-prompt (#'worker/execute-claims! {:id "w0"} ["task-real"]))
                           "Exact result bytes")))))

(let [{:keys [fail error]} (t/run-tests 'agentnet.buddy-prompt-test)]
  (when (pos? (+ fail error)) (throw (ex-info "Buddy prompt tests failed" {}))))
