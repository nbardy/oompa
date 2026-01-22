(ns agentnet.orchestrator
  "Main orchestration loop coordinating agents, worktrees, and merges.

   The orchestrator manages the full lifecycle:
     1. Initialize worktree pool
     2. Load task queue
     3. Dispatch tasks to workers
     4. Run propose/review loops
     5. Merge approved changes
     6. Log results

   Design:
     - Workers are lightweight (just state, not threads)
     - core.async for parallelism
     - Each worker owns one worktree
     - Tasks flow: queue -> worker -> review -> merge -> done

   Concurrency Model:
     - N workers, N worktrees
     - Each worker processes one task at a time
     - Workers can run in parallel (different worktrees)
     - Merges are serialized (one at a time to main)"
  (:require [agentnet.schema :as schema]
            [agentnet.core :as core]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [agentnet.review :as review]
            [agentnet.merge :as merge]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; =============================================================================
;; Function Specs
;; =============================================================================

;; create-orchestrator : OrchestratorConfig -> OrchestratorState
;; Initialize orchestrator with workers and worktree pool

;; run! : OrchestratorState -> OrchestratorState
;; Process all tasks to completion

;; process-task! : Worker, Task, Context -> TaskResult
;; Full lifecycle for one task (propose, review, merge)

;; shutdown! : OrchestratorState -> nil
;; Clean up resources

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const TASKS_PATH "config/tasks.edn")
(def ^:const POLICY_PATH "config/policy.edn")
(def ^:const RUNS_DIR "runs")

;; =============================================================================
;; State Management
;; =============================================================================

(defn- now-ms []
  (System/currentTimeMillis))

(defn- read-edn [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (edn/read {:eof nil} r)))))

(defn- load-tasks []
  (or (read-edn TASKS_PATH) []))

(defn- load-policy []
  (or (read-edn POLICY_PATH)
      {:allow ["src/**" "tests/**"]
       :deny ["secrets/**" "**/*.pem"]
       :limits {:max-lines-added 800
                :max-review-attempts 5}}))

;; =============================================================================
;; Worker Management
;; =============================================================================

(defn- create-worker [id worktree-instance]
  {:id id
   :status :idle
   :worktree worktree-instance
   :current-task nil
   :review-loop nil})

(defn- assign-task [worker task]
  (assoc worker
         :status :proposing
         :current-task task))

(defn- complete-task [worker result]
  (assoc worker
         :status :idle
         :current-task nil
         :review-loop nil
         :last-result result))

;; =============================================================================
;; Task Processing
;; =============================================================================

(defn- build-context [tasks]
  (core/build-context {:tasks tasks
                       :policy (load-policy)
                       :recent-sec 180}))

(defn process-task!
  "Process a single task through full lifecycle.

   Steps:
     1. Run propose/review loop until approved
     2. Merge approved changes to main
     3. Clean up worktree

   Returns TaskResult map."
  [agent-config task context worktree opts]
  (let [start-time (now-ms)
        max-attempts (get-in opts [:limits :max-review-attempts] 5)

        ;; Run review loop
        loop-result (review/review-task!
                      agent-config
                      task
                      context
                      worktree
                      {:max-attempts max-attempts})

        _ (println (format "[%s] Review: %s (%d attempts)"
                           (:id task)
                           (name (:status loop-result))
                           (review/attempt-count loop-result)))

        ;; Merge if approved
        merge-result (when (review/approved? loop-result)
                       (println (format "[%s] Merging..." (:id task)))
                       (merge/merge-worktree! worktree
                                              {:strategy :no-ff
                                               :dry-run (:dry-run opts)}))]

    {:task-id (:id task)
     :status (cond
               (and merge-result (= :merged (:status merge-result))) :merged
               (review/approved? loop-result) :merge-failed
               (review/exhausted? loop-result) :review-exhausted
               :else :failed)
     :worker-id (:id worktree)
     :started-at start-time
     :completed-at (now-ms)
     :review-attempts (review/attempt-count loop-result)
     :merge-result merge-result
     :error (or (:error loop-result)
                (:error merge-result))}))

;; =============================================================================
;; Orchestrator Lifecycle
;; =============================================================================

(defn create-orchestrator
  "Initialize orchestrator with config.

   Arguments:
     config - OrchestratorConfig

   Returns OrchestratorState"
  [{:keys [worker-count harness model worktree-root dry-run] :as config}]
  (schema/assert-valid schema/valid-orchestrator-config? config "OrchestratorConfig")

  (let [model-str (if model (format " (model: %s)" model) "")]
    (println (format "Initializing %d workers with %s harness%s..."
                     worker-count (name harness) model-str)))

  ;; Initialize worktree pool
  (let [pool (worktree/init-pool! config)
        workers (mapv (fn [wt]
                        (create-worker (:id wt) wt))
                      pool)
        tasks (load-tasks)]

    (println (format "Loaded %d tasks, %d worktrees ready"
                     (count tasks) (count workers)))

    {:config config
     :workers workers
     :worktree-pool pool
     :task-queue (vec tasks)
     :completed []
     :failed []
     :started-at (now-ms)}))

(defn run!
  "Run orchestrator: process all tasks with parallel workers.

   Uses core.async to parallelize across workers.
   Returns updated OrchestratorState."
  [state]
  (let [{:keys [config workers worktree-pool task-queue]} state
        {:keys [worker-count harness model dry-run]} config
        policy (load-policy)

        agent-config {:type harness
                      :model model
                      :sandbox :workspace-write
                      :timeout-seconds 300}

        context (build-context task-queue)

        ;; Channels for task distribution
        task-ch (async/chan)
        result-ch (async/chan)
        merge-ch (async/chan)  ; Serialize merges

        ;; Worker processes
        worker-procs
        (doall
          (for [worker workers]
            (async/go-loop []
              (when-let [task (async/<! task-ch)]
                (let [wt (:worktree worker)
                      result (try
                               (process-task! agent-config task context wt
                                              {:dry-run dry-run
                                               :limits (:limits policy)})
                               (catch Exception e
                                 {:task-id (:id task)
                                  :status :error
                                  :worker-id (:id worker)
                                  :error (.getMessage e)}))]
                  (async/>! result-ch result)
                  ;; Reset worktree for next task
                  (worktree/release! worktree-pool (:id wt) {:reset? true})
                  (recur))))))

        ;; Feed tasks to workers
        _ (async/go
            (doseq [task task-queue]
              (async/>! task-ch task))
            (async/close! task-ch))

        ;; Collect results
        results (loop [remaining (count task-queue)
                       acc []]
                  (if (zero? remaining)
                    acc
                    (if-let [result (async/<!! result-ch)]
                      (do
                        (println (format "[%s] -> %s"
                                         (:task-id result)
                                         (name (:status result))))
                        (recur (dec remaining) (conj acc result)))
                      acc)))]

    ;; Close channels
    (async/close! result-ch)

    ;; Update state
    (assoc state
           :completed (filterv #(= :merged (:status %)) results)
           :failed (filterv #(not= :merged (:status %)) results)
           :run-log results
           :finished-at (now-ms))))

(defn shutdown!
  "Clean up orchestrator resources"
  [state]
  (println "Shutting down orchestrator...")
  (worktree/cleanup-pool! (:worktree-pool state))
  nil)

;; =============================================================================
;; Logging
;; =============================================================================

(defn- ensure-dir! [path]
  (.mkdirs (io/file path)))

(defn save-run-log!
  "Save run results to JSONL file"
  [state]
  (ensure-dir! RUNS_DIR)
  (let [timestamp (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
        fname (format "run-%s.jsonl"
                      (.format timestamp (java.time.LocalDateTime/now)))
        path (io/file RUNS_DIR fname)]
    (doseq [entry (:run-log state)]
      (spit path (str (json/generate-string entry) "\n") :append true))
    (println (format "Run log: %s" (.getPath path)))
    (.getPath path)))

(defn print-summary
  "Print run summary to stdout"
  [state]
  (let [{:keys [completed failed started-at finished-at]} state
        duration-sec (/ (- (or finished-at (now-ms)) started-at) 1000.0)]
    (println)
    (println "=== Run Summary ===")
    (println (format "Duration: %.1fs" duration-sec))
    (println (format "Completed: %d" (count completed)))
    (println (format "Failed: %d" (count failed)))
    (when (seq failed)
      (println "Failed tasks:")
      (doseq [{:keys [task-id status error]} failed]
        (println (format "  - %s: %s%s"
                         task-id
                         (name status)
                         (if error (str " (" error ")") "")))))))

;; =============================================================================
;; Convenience API
;; =============================================================================

(defn run-once!
  "Convenience: create orchestrator, run, save log, shutdown.

   Arguments:
     opts - {:workers N, :harness :codex|:claude, :model string, :dry-run bool}

   Returns run log path"
  [opts]
  (let [config {:worker-count (or (:workers opts) 2)
                :harness (or (:harness opts) :codex)
                :model (:model opts)
                :worktree-root ".workers"
                :dry-run (:dry-run opts false)
                :policy (load-policy)}
        state (-> (create-orchestrator config)
                  (run!))]
    (print-summary state)
    (let [log-path (save-run-log! state)]
      (when-not (:keep-worktrees opts)
        (shutdown! state))
      log-path)))

(defn run-loop!
  "Run orchestrator in a loop N times (like the bash loop).

   Arguments:
     iterations - Number of times to run
     opts       - Same as run-once!

   Returns vector of log paths"
  [iterations opts]
  (loop [i 0
         logs []]
    (if (< i iterations)
      (do
        (println (format "\n=== Iteration %d/%d ===" (inc i) iterations))
        (let [log (run-once! opts)]
          (recur (inc i) (conj logs log))))
      logs)))
