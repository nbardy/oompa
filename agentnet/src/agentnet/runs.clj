(ns agentnet.runs
  "Structured persistence for swarm runs — event-sourced immutable logs.

   Layout:
     runs/{swarm-id}/
       started.json       — swarm start event (PID, worker configs, planner)
       stopped.json       — swarm stop event (reason, optional error)
       cycles/
         {worker-id}-c{N}.json  — per-cycle event log (one complete work unit)
       reviews/
         {worker-id}-c{N}-r{round}.json  — per-cycle review log

   All state is derived by reading event logs. No mutable summary files.
   Written as JSON for easy consumption by claude-web-view and CLI.
   All writes are atomic (write to .tmp, rename) to avoid partial reads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; =============================================================================
;; Paths
;; =============================================================================

(def ^:const RUNS_ROOT "runs")

(defn- run-dir [swarm-id]
  (str RUNS_ROOT "/" swarm-id))

(defn- reviews-dir [swarm-id]
  (str (run-dir swarm-id) "/reviews"))

(defn- cycles-dir [swarm-id]
  (str (run-dir swarm-id) "/cycles"))

(defn- ensure-run-dirs! [swarm-id]
  (.mkdirs (io/file (reviews-dir swarm-id)))
  (.mkdirs (io/file (cycles-dir swarm-id))))

;; =============================================================================
;; Atomic Write
;; =============================================================================

(defn- write-json!
  "Write JSON data atomically: write to .tmp, rename to final path."
  [path data]
  (let [f (io/file path)
        tmp (io/file (str path ".tmp"))]
    (.mkdirs (.getParentFile f))
    (spit tmp (json/generate-string data {:pretty true}))
    (.renameTo tmp f)))

;; =============================================================================
;; Started Event — written at swarm start
;; =============================================================================

(defn write-started!
  "Write the started event when a swarm begins.
   Contains: start time, PID, worker configs, planner output, swarm metadata."
  [swarm-id {:keys [workers planner-config reviewer-config config-file]}]
  (ensure-run-dirs! swarm-id)
  (write-json! (str (run-dir swarm-id) "/started.json")
               {:swarm-id swarm-id
                :started-at (str (java.time.Instant/now))
                :pid (.pid (java.lang.ProcessHandle/current))
                :config-file config-file
                :workers (mapv (fn [w]
                                 {:id (:id w)
                                  :harness (name (:harness w))
                                  :model (:model w)
                                  :reasoning (:reasoning w)
                                  :max-cycles (:max-cycles w)
                                  :max-resumes (:max-resumes w)
                                  :can-plan (:can-plan w)
                                  :prompts (:prompts w)})
                               workers)
                :planner (when planner-config
                           {:harness (name (:harness planner-config))
                            :model (:model planner-config)
                            :prompts (:prompts planner-config)
                            :max-pending (:max-pending planner-config)})
                :reviewer (when reviewer-config
                            {:harness (name (:harness reviewer-config))
                             :model (:model reviewer-config)
                             :prompts (:prompts reviewer-config)})}))

;; =============================================================================
;; Stopped Event — written at swarm end
;; =============================================================================

(defn write-stopped!
  "Write the stopped event when a swarm exits.
   reason: :completed (genuinely drained queue), :workers-exhausted (zero
   workers ended :completed and pending tasks remain), :interrupted, :error.
   Optional:
     :worker-outcomes — per-worker terminal outcome maps ({:id :status ...})
     :pending-count   — tasks still pending at stop time
   Audit (runs 80a33337/9f004a39): stopped.json previously always said
   \"completed\"/error nil even when every worker died in an error state, so
   dead runs went unnoticed for ~20h."
  [swarm-id reason & {:keys [error worker-outcomes pending-count]}]
  (write-json! (str (run-dir swarm-id) "/stopped.json")
               (cond-> {:swarm-id swarm-id
                        :stopped-at (str (java.time.Instant/now))
                        :reason (name reason)
                        :error error}
                 worker-outcomes (assoc :worker-outcomes worker-outcomes)
                 (some? pending-count) (assoc :pending-count pending-count))))

;; =============================================================================
;; Review Log — written after each review round
;; =============================================================================

(defn write-review-log!
  "Write a review log for one cycle of a worker.
   Contains: verdict, round number, full reviewer output, diff file list."
  [swarm-id worker-id cycle round
   {:keys [verdict output diff-files duration-ms]}]
  (ensure-run-dirs! swarm-id)
  (let [filename (format "%s-c%d-r%d.json" worker-id cycle round)]
    (write-json! (str (reviews-dir swarm-id) "/" filename)
                 {:worker-id worker-id
                  :cycle cycle
                  :round round
                  :verdict (name verdict)
                  :timestamp (str (java.time.Instant/now))
                  :output output
                  :duration-ms (or duration-ms 0)
                  :diff-files (vec diff-files)})))

;; =============================================================================
;; Cycle Log — written per cycle for real-time dashboard visibility
;; =============================================================================

(defn write-cycle-log!
  "Write a cycle event log for a worker.
   Contains: attempt, outcome, timing, claimed task IDs, recycled tasks,
   session-id, worktree-path, signals, and merge-sha.
   session-id is salvage's resume handle: it links to the agent-cli conversation
   on disk so an interrupted cycle can be resumed/recovered. attempt,
   worktree-path, signals (cumulative attempt chain), and merge-sha were
   previously emitted by emit-cycle-log! but dropped here — they are now
   persisted so the event log is complete. Written at cycle end so dashboards can
   track progress in real-time."
  [swarm-id worker-id cycle
   {:keys [attempt outcome duration-ms claimed-task-ids recycled-tasks
           error-snippet review-rounds session-id timing-ms
           worktree-path signals merge-sha]}]
  (when swarm-id
    (let [filename (format "%s-c%d.json" worker-id cycle)]
      (write-json! (str (cycles-dir swarm-id) "/" filename)
                   {:worker-id worker-id
                    :cycle cycle
                    :attempt attempt
                    :outcome (name outcome)
                    :timestamp (str (java.time.Instant/now))
                    :duration-ms duration-ms
                    :claimed-task-ids (or claimed-task-ids [])
                    :recycled-tasks (or recycled-tasks [])
                    :error-snippet error-snippet
                    :review-rounds (or review-rounds 0)
                    :session-id session-id
                    :worktree-path worktree-path
                    :signals (or signals [])
                    :merge-sha merge-sha
                    :timing-ms timing-ms}))))

;; =============================================================================
;; Worker State — live per-worker liveness for dashboards
;; =============================================================================

(defn write-worker-state!
  "Write runs/{swarm-id}/workers/{worker-id}.json — the worker's LIVE state.

   This is the one deliberate exception to \"no mutable summary files\": cycle
   logs are only written at cycle END, so mid-cycle there is no record that a
   worker is alive. Dashboards that derived status from the latest cycle file
   rendered a worker red/'done' for its entire next cycle after one bad cycle
   (observed run a98d3b2e: all three workers shown dead + 'All idle' while all
   three were mid-cycle-2). Overwritten at every cycle start and at worker
   terminal stop; atomic rename keeps readers consistent.

   status: \"running\" (cycle in progress) | \"stopped\" (worker exited; :reason
   carries the terminal status name, e.g. completed/error/fatal-error)."
  [swarm-id worker-id {:keys [status cycle attempt reason]}]
  (when swarm-id
    (write-json! (str (run-dir swarm-id) "/workers/" worker-id ".json")
                 (cond-> {:worker-id worker-id
                          :status status
                          :updated-at (str (java.time.Instant/now))}
                   cycle (assoc :cycle cycle)
                   attempt (assoc :attempt attempt)
                   reason (assoc :reason reason)))))

;; =============================================================================
;; Read helpers (for cmd-status, dashboards)
;; =============================================================================

(defn list-runs
  "List all swarm run directories, newest first."
  []
  (let [d (io/file RUNS_ROOT)]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(.isDirectory %))
           (sort-by #(.lastModified %) >)
           (mapv #(.getName %))))))

(defn read-started
  "Read started.json for a swarm."
  [swarm-id]
  (let [f (io/file (str (run-dir swarm-id) "/started.json"))]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

(defn read-stopped
  "Read stopped.json for a swarm. Returns nil if still running."
  [swarm-id]
  (let [f (io/file (str (run-dir swarm-id) "/stopped.json"))]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

(defn list-reviews
  "List all review logs for a swarm, sorted by filename."
  [swarm-id]
  (let [d (io/file (reviews-dir swarm-id))]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".json"))
           (sort-by #(.getName %))
           (mapv (fn [f] (json/parse-string (slurp f) true)))))))

(defn list-cycles
  "List all cycle logs for a swarm, sorted by filename."
  [swarm-id]
  (let [d (io/file (cycles-dir swarm-id))]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".json"))
           (sort-by #(.getName %))
           (mapv (fn [f] (json/parse-string (slurp f) true)))))))
