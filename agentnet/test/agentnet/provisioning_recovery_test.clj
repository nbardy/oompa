(ns agentnet.provisioning-recovery-test
  (:require [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.runs :as runs]
            [agentnet.cambium :as cambium]
            [clojure.test :refer [deftest is run-tests]]))

(def startup {:task_id "capture" :claim_revision 2 :attempt 0
              :held_reason "execution-blocked"
              :feedback {:error "Cambium creation failed; workspace retained if partially created: original"}})
(def resume-state (ns-resolve 'agentnet.worker 'resume-selected-state))
(def recover-task (ns-resolve 'agentnet.worker 'recover-provisioning-task!))

(deftest legacy-no-model-provisioning-recovery-keeps-original-claim-and-deadline
  (let [restored (atom [])
        task (atom {:id "capture" :status "in_progress" :revision 2 :paused false})
        saved {:cycle 2 :attempt 0 :accepted_count 1 :deadline_ms 123456
               :held_executions {:capture startup}}
        active-source {:path "/original-force" :name "original-force"}
        force-state {:task_id "force" :claim_revision 9 :attempt 9 :session_id "original"
                     :workspace active-source :validator_digest nil :held_reason "model-blocked"}]
    (with-redefs [runs/read-worker-state (constantly {:execution (assoc-in saved [:held_executions :force] force-state)})
                  cambium/list-workspaces (constantly [active-source])
                  cambium/claim-existing! (fn [_ ws] (is (= active-source ws)))
                  tasks/get-task (fn [id] (if (= id "capture") @task {:id "force" :status "blocked"}))
                  tasks/restore-claim! (fn [& args] (swap! restored conj args))]
      (let [resumed (resume-state {:resume-swarm-id "same-window" :id "w0"} {:validators {}} 234567)
            held (:held_executions resumed)]
        (is (= 123456 (:deadline_ms resumed)))
        (is (= force-state (get held "force")))
        (is (true? (get-in held ["capture" :startup_recovery_ready])))
        (is (nil? (get-in held ["capture" :workspace])))
        (is (= "legacy-creation-failure" (get-in held ["capture" :provisioning_failures 0 :kind])))
        (is (empty? @restored))
        (doseq [changed [{:status "blocked" :revision 2 :paused false}
                         {:status "in_progress" :revision 3 :paused false}
                         {:status "in_progress" :revision 2 :paused true}]]
          (reset! task (merge {:id "capture"} changed))
          (is (nil? (recover-task held))))
        (is (empty? @restored))
        (reset! task {:id "capture" :status "in_progress" :revision 2 :paused false})
        (is (= @task (recover-task held)))
        (is (= [["capture" 2]] @restored))))))

(deftest second-provisioning-failure-stays-held-and-model-history-cannot-bypass-source
  (with-redefs [cambium/list-workspaces (constantly []) tasks/get-task (constantly {})]
    (let [twice (assoc startup :provisioning_failures [{:kind "creation-failure"} {:kind "creation-failure"}])]
      (with-redefs [runs/read-worker-state (constantly {:execution {:deadline_ms 123456 :held_executions {:capture twice}}})]
        (let [resumed (resume-state {:resume-swarm-id "same-window" :id "w0"} {:validators {}} 234567)]
          (is (nil? (get-in resumed [:held_executions "capture" :startup_recovery_ready]))))))
    (doseq [model-history [{:attempt 1} {:session_id "existing-model"} {:attempt_history [{:attempt 1}]}]]
      (with-redefs [runs/read-worker-state (constantly {:execution {:deadline_ms 123456
                                                                  :held_executions {:capture (merge startup model-history)}}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact registered source"
                             (resume-state {:resume-swarm-id "same-window" :id "w0"} {:validators {}} 234567)))))))

(let [{:keys [fail error]} (run-tests 'agentnet.provisioning-recovery-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
