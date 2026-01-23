(ns agentnet.worker
  "Self-directed worker execution.

   Workers:
   1. Claim tasks from tasks/pending/ (mv → current/)
   2. Execute task in worktree
   3. Complete task (mv current/ → complete/)
   4. Can create new tasks in pending/
   5. Exit on __DONE__ signal

   No separate orchestrator - workers self-organize."
  (:require [agentnet.tasks :as tasks]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Worker State
;; =============================================================================

(defn create-worker
  "Create a worker config"
  [{:keys [id harness model iterations custom-prompt review-harness review-model]}]
  {:id id
   :harness (or harness :codex)
   :model model
   :iterations (or iterations 10)
   :custom-prompt custom-prompt
   :review-harness review-harness
   :review-model review-model
   :completed 0
   :status :idle})

;; =============================================================================
;; Task Execution
;; =============================================================================

(defn- build-context
  "Build context for agent prompts"
  []
  (let [pending (tasks/list-pending)
        current (tasks/list-current)
        complete (tasks/list-complete)]
    {:pending_count (count pending)
     :current_count (count current)
     :complete_count (count complete)
     :pending_tasks (str/join "\n" (map #(str "- " (:id %) ": " (:summary %)) pending))
     :task_status (format "Pending: %d, In Progress: %d, Complete: %d"
                          (count pending) (count current) (count complete))}))

(defn- run-agent!
  "Run agent with prompt, return {:output string, :done? bool, :exit int}"
  [{:keys [harness model custom-prompt]} worktree-path context]
  (let [;; Load prompt (check config/prompts/ as default)
        prompt-content (or (agent/load-custom-prompt custom-prompt)
                           (agent/load-custom-prompt "config/prompts/worker.md")
                           "Goal: Match spec.md\nProcess: Create/claim tasks in tasks/{pending,current,complete}/*.edn\nMethod: Isolate changes to your worktree, commit and merge when complete")

        ;; Inject worktree and context
        full-prompt (str "Worktree: " worktree-path "\n"
                         "Task Status: " (:task_status context) "\n\n"
                         prompt-content)

        ;; Build command
        cmd (case harness
              :codex (cond-> ["codex" "exec" "--full-auto" "--skip-git-repo-check"
                              "-C" worktree-path "--sandbox" "workspace-write"]
                       model (into ["--model" model])
                       true (conj "--" full-prompt))
              :claude (cond-> ["claude" "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model])))

        ;; Run agent
        result (try
                 (if (= harness :claude)
                   ;; Claude reads from stdin
                   (process/sh cmd {:in full-prompt :out :string :err :string})
                   ;; Codex takes prompt as arg
                   (process/sh cmd {:out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

    {:output (:out result)
     :exit (:exit result)
     :done? (agent/done-signal? (:out result))}))

(defn execute-iteration!
  "Execute one iteration of work.

   Returns {:status :done|:continue|:error, :task task-or-nil}"
  [worker iteration]
  (let [worker-id (:id worker)
        wt-id (format ".w%s-i%d" worker-id iteration)

        ;; Create worktree
        _ (println (format "[%s] Starting iteration %d" worker-id iteration))
        wt-path (str (System/getProperty "user.dir") "/" wt-id)]

    (try
      ;; Setup worktree
      (process/sh ["git" "worktree" "add" wt-id "-b" wt-id])

      ;; Build context
      (let [context (build-context)

            ;; Run agent
            {:keys [output exit done?]} (run-agent! worker wt-path context)]

        (cond
          ;; Agent signaled done
          done?
          (do
            (println (format "[%s] Received __DONE__ signal" worker-id))
            {:status :done})

          ;; Agent errored
          (not (zero? exit))
          (do
            (println (format "[%s] Agent error (exit %d)" worker-id exit))
            {:status :error :exit exit})

          ;; Success
          :else
          (do
            (println (format "[%s] Iteration %d complete" worker-id iteration))
            {:status :continue})))

      (finally
        ;; Cleanup worktree
        (process/sh ["git" "worktree" "remove" wt-id "--force"])))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

(defn run-worker!
  "Run worker loop until done or iterations exhausted.

   Returns final worker state."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id iterations]} worker]
    (println (format "[%s] Starting worker (%s:%s, %d iterations)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     iterations))

    (loop [iter 1
           completed 0]
      (if (> iter iterations)
        (do
          (println (format "[%s] Completed %d iterations" id completed))
          (assoc worker :completed completed :status :exhausted))

        (let [{:keys [status]} (execute-iteration! worker iter)]
          (case status
            :done
            (do
              (println (format "[%s] Worker done after %d iterations" id iter))
              (assoc worker :completed iter :status :done))

            :error
            (do
              (println (format "[%s] Worker error at iteration %d, continuing..." id iter))
              (recur (inc iter) completed))

            :continue
            (recur (inc iter) (inc completed))))))))

;; =============================================================================
;; Multi-Worker Execution
;; =============================================================================

(defn run-workers!
  "Run multiple workers in parallel.

   Arguments:
     workers - seq of worker configs

   Returns seq of final worker states."
  [workers]
  (tasks/ensure-dirs!)
  (println (format "Launching %d workers..." (count workers)))

  (let [futures (doall
                  (map-indexed
                    (fn [idx worker]
                      (let [worker (assoc worker :id (or (:id worker) (str "w" idx)))]
                        (future (run-worker! worker))))
                    workers))]

    (println "All workers launched. Waiting for completion...")
    (let [results (mapv deref futures)]
      (println "\nAll workers complete.")
      (doseq [w results]
        (println (format "  [%s] %s - %d iterations"
                         (:id w)
                         (name (:status w))
                         (:completed w))))
      results)))
