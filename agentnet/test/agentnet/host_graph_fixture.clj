;; Actual-port host contract driver. Only implementation/reviewer/Cambium
;; lifecycle are fixture doubles; canonical HTTP, source checks and host accept
;; execute the real adapters. No provider/model/native process is launched.
(require '[agentnet.worker :as worker]
         '[agentnet.tasks :as tasks]
         '[agentnet.runs :as runs]
         '[agentnet.cambium :as cambium]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(let [spec (json/parse-string (slurp (first *command-line-args*)) true)
      config (json/parse-string (slurp (:configPath spec)) true)
      prior (or (:resumeSwarmId spec) (:resume_swarm_id config))
      worker-id (or (:workerId spec) "w0")
      output (or (:outputSwarmId spec) "consumer-proof")
      spaces (vec (or (:newTaskWorkspaces spec) [(:newTaskWorkspace spec)]))
      registered (vec (concat (:registeredWorkspaces spec) spaces))
      created (atom 0) models (atom [])
      shutdown (var-get #'worker/shutdown-requested?)]
  (when-not (and (seq spaces) (every? :path spaces)) (throw (ex-info "Fixture requires source workspaces" {})))
  (tasks/configure! (:task_backend config))
  (reset! shutdown false)
  (try
    (with-redefs [worker/create-iteration-worktree!
                  (fn [& _]
                    (or (get spaces (dec (swap! created inc)))
                        (throw (ex-info "Fixture has no additional provisioned workspace" {}))))
                  cambium/list-workspaces (fn [_] registered)
                  cambium/claim-existing! (fn [_ ws] ws)
                  cambium/remove! (fn [& _] {:removed? false})
                  worker/run-agent!
                  (fn [_ path _ session resume? & _]
                    (when-let [write (:modelWrite spec)]
                      (spit (io/file path (:path write)) (:contents write))
                      (let [result (process/sh ["git" "add" "--" (:path write)] {:dir path})]
                        (when-not (zero? (:exit result)) (throw (ex-info "Fixture stage failed" {}))))
                      (let [result (process/sh ["git" "commit" "-m" "fixture source prerequisite"] {:dir path})]
                        (when-not (zero? (:exit result)) (throw (ex-info "Fixture commit failed" {})))))
                    (swap! models conj {:path path :session_id session :resume resume?})
                    {:exit 0 :merge? true :session-id (or session "fixture-source-session") :output "source fixture ready"})
                  worker/run-reviewer! (fn [& _] {:verdict :approved :exit 0 :output "VERDICT: APPROVED"})]
      (let [result (worker/run-selected-worker!
                     {:id worker-id :swarm-id output :resume-swarm-id prior :max-cycles 100 :max-resumes 100
                      :prompts [] :reviewers [{:harness :claude}]
                      :task-execution (:task_execution config)})]
        (println (json/generate-string
                   {:result (select-keys result [:status :completed :queue_audit :queue_audit_changed])
                    :execution (:execution (runs/read-worker-state output worker-id))
                    :model_calls @models :created @created}))))
    (finally (reset! shutdown false) (tasks/configure! nil))))
