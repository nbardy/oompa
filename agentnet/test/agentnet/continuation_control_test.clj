(ns agentnet.continuation-control-test
  (:require [agentnet.worker :as worker]
            [agentnet.task-execution :as execution]
            [agentnet.tasks :as tasks]
            [agentnet.runs :as runs]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]))

(def config
  {:id "w0" :swarm-id "continuation" :max-cycles 10 :max-resumes 20
   :task-execution {:wall_time_seconds 30 :attempt_timeout_seconds 1
                    :validators {} :prepare_only_task_ids ["one"]}})

(defn private-var [symbol] (ns-resolve 'agentnet.worker symbol))
(def shutdown (var-get #'worker/shutdown-requested?))

(defn run-fixture [dir state overrides]
  (reset! shutdown false)
  (try
    (with-redefs-fn
      (merge {#'tasks/buddies? (constantly true)
              (private-var 'continuation-control-dir) (constantly dir)
              (private-var 'resume-selected-state) (fn [& _] state)
              #'tasks/list-all (fn [] [{:id "one" :status "blocked"}])
              #'tasks/get-task (fn [_] {:id "one" :status "blocked" :revision 4})
              #'runs/write-worker-state! (fn [& _])}
             overrides)
      #(worker/run-worker! config))
    (finally (reset! shutdown false))))

(deftest reopened-held-task-waits-for-exact-request-removal
  (let [dir (fs/create-temp-dir {:prefix "oompa-continuation-"})
        request (io/file (str dir) "continuation-request.json")
        deadline (+ (execution/now-ms) 20000)
        held {:task_id "one" :claim_revision 3 :held_task_revision 4 :attempt 9
              :session_id "original-session" :attempt_history [{:attempt 9 :output "held"}]
              :workspace {:path "/retained/source"} :phase "implementing"}
        initial {:cycle 2 :attempt 0 :accepted_count 0 :deadline_ms deadline
                 :held_executions {"one" held}}
        status (atom "blocked") snapshots (atom []) selected (atom 0) models (atom [])
        persisted (atom nil) write-state! runs/write-worker-state!]
    (try
      ;; Even an expired diagnostic request cannot prove its child is dead.
      (spit request (json/generate-string {:id "diagnostic-A" :deadline_ms 1}))
      (let [result
            (run-fixture
             (str dir) initial
             {#'tasks/get-task (fn [_] {:id "one" :status @status :revision 5})
              (ns-resolve 'agentnet.runs 'write-json!) (fn [_ data] (reset! persisted data))
              (private-var 'selected-next-task!)
              (fn [& _]
                (swap! selected inc)
                (is (not (.exists request)))
                (when (= "open" @status)
                  (reset! status "in_progress")
                  {:id "one" :status "in_progress" :revision 5}))
              #'runs/write-worker-state!
              (fn [_ _ state]
                (write-state! "continuation" "w0" state)
                (swap! snapshots conj @persisted)
                (when (= "host-continuation" (:reason state))
                  (is (= "waiting" (:status state)))
                  (is (= "diagnostic-A" (get-in state [:execution :host_continuation_id])))
                  (is (= initial (dissoc (:execution state) :host_continuation_id)))
                  (is (zero? @selected))
                  (is (empty? @models))
                  (if (= "blocked" @status)
                    (reset! status "open")
                    (fs/delete request))))
              #'worker/run-agent!
              (fn [_ path _ session resume? & _]
                (swap! models conj {:path path :session session :resume? resume?})
                (reset! shutdown true)
                {:exit 0 :output "Still working" :session-id session})
              #'worker/run-reviewer! (fn [& _] (throw (ex-info "Unexpected review" {})))
              #'tasks/request-acceptance! (fn [& _] (throw (ex-info "Unexpected validation" {})))})
            waits (filter #(= "host-continuation" (:reason %)) @snapshots)
            saved (:execution (last @snapshots))]
        (is (= :interrupted (:status result)))
        (is (= 2 (count waits)))
        (is (not= (:updated-at (first waits)) (:updated-at (second waits))))
        (is (= 1 @selected))
        (is (= [{:path "/retained/source" :session "original-session" :resume? true}] @models))
        (is (= deadline (:deadline_ms saved)))
        (is (= 10 (:attempt saved)))
        (is (= 2 (count (:attempt_history saved))))
        (is (nil? (:host_continuation_id saved))))
      (finally (fs/delete-tree dir)))))

(deftest continuation-wait-respects-shutdown-and-original-deadline
  (doseq [mode [:shutdown :deadline]]
    (let [dir (fs/create-temp-dir {:prefix "oompa-continuation-stop-"})
          request (io/file (str dir) "continuation-request.json")
          deadline (+ (execution/now-ms) (if (= mode :deadline) 30 10000))
          initial {:cycle 1 :attempt 0 :accepted_count 0 :deadline_ms deadline}
          snapshots (atom []) started (execution/now-ms)]
      (try
        (spit request "{\"id\":\"stop-test\"}")
        (let [result (run-fixture
                      (str dir) initial
                      {(private-var 'selected-next-task!) (fn [& _] (throw (ex-info "Unexpected selection" {})))
                       #'runs/write-worker-state!
                       (fn [_ _ state]
                         (swap! snapshots conj state)
                         (when (and (= mode :shutdown) (= "waiting" (:status state)))
                           (reset! shutdown true)))})]
          (is (= (if (= mode :shutdown) :interrupted :wall-time-exhausted) (:status result)))
          (is (= "host-continuation" (:reason (first @snapshots))))
          (is (= deadline (get-in (last @snapshots) [:execution :deadline_ms])))
          (is (= "stopped" (:status (last @snapshots))))
          (is (.exists request))
          (is (< (- (execution/now-ms) started) 2000)))
        (finally (fs/delete-tree dir))))))

(deftest invalid-requests-fail-closed-and-absence-preserves-selection
  (let [dir (fs/create-temp-dir {:prefix "oompa-continuation-invalid-"})
        request (io/file (str dir) "continuation-request.json")
        state {:cycle 1 :attempt 0 :accepted_count 0 :deadline_ms (+ (execution/now-ms) 10000)}]
    (try
      (doseq [contents ["null" "false" "[]" "{}" "{\"id\":1}" "{\"id\":\"\"}" "{\"id\":\"  \"}" "{"]]
        (spit request contents)
        (is (= :continuation-control-invalid
               (:status (run-fixture (str dir) state
                                     {(private-var 'selected-next-task!) (fn [& _] (throw (ex-info "Unexpected selection" {})))})))))
      (is (= :continuation-control-invalid (:status (run-fixture (str dir "/missing") state {}))))
      (fs/delete request)
      (doseq [control [nil (str dir)]]
        (let [selected (atom false)
              result (run-fixture control state
                                  {(private-var 'selected-next-task!)
                                   (fn [& _] (reset! selected true) (reset! shutdown true) nil)})]
          (is @selected)
          (is (= :interrupted (:status result)))))
      (finally (fs/delete-tree dir)))))

(deftest active-execution-never-acknowledges-idle
  (doseq [phase ["implementing" "validating"]]
    (let [dir (fs/create-temp-dir {:prefix "oompa-continuation-active-"})
          state {:cycle 1 :attempt 2 :accepted_count 0 :deadline_ms (+ (execution/now-ms) 10000)
                 :task_id "one" :claim_revision 5 :phase phase :session_id "active-session"
                 :workspace {:path "/active/source"}}
          snapshots (atom []) calls (atom 0)]
      (try
        (spit (io/file (str dir) "continuation-request.json") "{\"id\":\"not-idle\"}")
        (let [result (run-fixture
                      (str dir) state
                      {#'tasks/get-task (fn [_] {:id "one" :status "in_progress" :revision 5})
                       #'runs/write-worker-state! (fn [_ _ state] (swap! snapshots conj state))
                       #'worker/run-agent! (fn [& _] (swap! calls inc) (reset! shutdown true)
                                            {:exit 0 :output "Working" :session-id "active-session"})
                       #'tasks/request-acceptance! (fn [& _] (swap! calls inc) (reset! shutdown true)
                                                    {:kind "accepted"})})]
          (is (= :interrupted (:status result)))
          (is (= 1 @calls))
          (is (not-any? #(= "waiting" (:status %)) @snapshots))
          (is (every? #(nil? (get-in % [:execution :host_continuation_id])) @snapshots))
          (is (= (:deadline_ms state) (get-in (last @snapshots) [:execution :deadline_ms]))))
        (finally (fs/delete-tree dir))))))

(deftest started-metadata-records-inherited-control-directory
  (let [dir (fs/create-temp-dir {:prefix "oompa-continuation-env-"})]
    (try
      (let [result (process/sh
                    ["bb" "-cp" (str (fs/absolutize "agentnet/src")) "-e"
                     (str "(require '[agentnet.runs :as runs]) "
                          "(with-redefs-fn {(ns-resolve 'agentnet.runs 'ensure-run-dirs!) (fn [& _]) "
                          "(ns-resolve 'agentnet.runs 'write-json!) "
                          "(fn [_ data] (println (:continuation-control-dir data)))} "
                          "#(runs/write-started! \"proof\" {:workers []}))")]
                    {:env {"OOMPA_CONTINUATION_CONTROL_DIR" (str dir)} :out :string :err :string})]
        (is (zero? (:exit result)))
        (is (= (str dir "\n") (:out result))))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (run-tests 'agentnet.continuation-control-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
