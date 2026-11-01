(ns agentnet.worker-eligibility-test
  (:require [agentnet.cli :as cli]
            [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.buddy-tasks :as buddy]
            [agentnet.buddy-tasks-test :as fixture]
            [clojure.test :refer [deftest is run-tests]]))

(def ids ["task_01a15f8d-a83e-74d5-9163-b5430eb7b79a"
          "task_01a15f8e-48e9-705f-9fa3-cb94ce2b3566"
          "task_01a15f8e-4a1c-7130-a142-b4783eee7c8c"
          "task_01a15f8e-4e1d-7164-ba63-c0997be5594c"
          "task_01a15f8e-5055-7553-bdd9-ccbf39e79741"])
(defn configured [config]
  (cli/configured-worker 0 (merge {:model "codex:gpt-6.1-sol:medium" :max_cycle 1} config)
                         "fixture" {} []))

(deftest typed-selector-and-cli-forwarding
  (is (nil? (:task-ids (configured {}))))
  (is (= #{} (:task-ids (configured {:task_ids []}))))
  (is (= (set ids) (:task-ids (configured {:task_ids ids}))))
  (doseq [bad [nil false "one" {} [nil] [2] [""] [" "]]]
    (is (thrown? Exception (configured {:task_ids bad})))))

(deftest real-buddy-claim-and-selected-scheduler-enforce-eligibility
  (let [store (atom (into {} (map #(vector % (fixture/task % "open")) ids)))
        calls (atom [])
        gpu (configured {:task_ids [] :can_claim_gpu true})
        source (configured {:task_ids ids :can_claim_gpu false})
        config {:validators (zipmap ids (repeat {:command ["not-executed"]}))}]
    (tasks/configure! (assoc fixture/config :task_ids ids))
    (try
      (binding [buddy/*transport* (fixture/fake-transport store calls)]
        (doseq [id ids]
          (is (= "task-ineligible" (:reason (#'worker/task-claim-denial gpu {:id id})))))
        (is (= ids (:failed (#'worker/execute-claims! gpu ids))))
        (is (nil? (#'worker/selected-next-task! gpu config {})))
        (is (empty? (filter #(= "task_write" (first %)) @calls)))
        (is (every? #(= "open" (:status %)) (vals @store)))
        ;; Actual scheduler claims each canonical child through the Buddy backend.
        (doseq [id ids]
          (is (= id (:id (#'worker/selected-next-task! source config {}))))
          (is (= "in_progress" (get-in @store [id :status]))))
        (doseq [id ids] (swap! store assoc-in [id :status] "open"))
        (is (= ids (:claimed (#'worker/execute-claims! source ids))))
        ;; Missing selector keeps legacy explicit claiming; other guards still apply.
        (swap! store assoc-in [(first ids) :status] "open")
        (is (= [(first ids)] (:claimed (#'worker/execute-claims! (configured {}) [(first ids)]))))
        (is (= "gpu-restricted" (:reason (#'worker/task-claim-denial source {:id (first ids) :gpu_heavy true}))))
        (is (= "dependency-missing" (:reason (#'worker/task-claim-denial source {:id (first ids) :depends_on ["missing"]}))))
        (is (= "role-mismatch" (:reason (#'worker/task-claim-denial (assoc source :role "source") {:id (first ids) :role_hint "other"})))))
      (finally (tasks/configure! nil)))))

(let [{:keys [fail error]} (run-tests 'agentnet.worker-eligibility-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
