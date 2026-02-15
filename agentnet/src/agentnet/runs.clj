(ns agentnet.runs
  "Structured persistence for swarm runs.

   Layout:
     runs/{swarm-id}/
       run.json          — start time, worker configs, planner output
       summary.json      — final metrics per worker, aggregate stats
       iterations/
         {worker-id}-i{N}.json  — per-iteration event log (started → outcome)
       reviews/
         {worker-id}-i{N}-r{round}.json  — per-iteration review log

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

(defn- iterations-dir [swarm-id]
  (str (run-dir swarm-id) "/iterations"))

(defn- ensure-run-dirs! [swarm-id]
  (.mkdirs (io/file (reviews-dir swarm-id)))
  (.mkdirs (io/file (iterations-dir swarm-id))))

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
;; Run Log — written at swarm start
;; =============================================================================

(defn write-run-log!
  "Write the initial run log when a swarm starts.
   Contains: start time, worker configs, planner output, swarm metadata."
  [swarm-id {:keys [workers planner-config reviewer-config config-file]}]
  (ensure-run-dirs! swarm-id)
  (write-json! (str (run-dir swarm-id) "/run.json")
               {:swarm-id swarm-id
                :started-at (str (java.time.Instant/now))
                :config-file config-file
                :workers (mapv (fn [w]
                                 {:id (:id w)
                                  :harness (name (:harness w))
                                  :model (:model w)
                                  :reasoning (:reasoning w)
                                  :iterations (:iterations w)
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
;; Review Log — written after each review round
;; =============================================================================

(defn write-review-log!
  "Write a review log for one iteration of a worker.
   Contains: verdict, round number, full reviewer output, diff file list."
  [swarm-id worker-id iteration round
   {:keys [verdict output diff-files]}]
  (ensure-run-dirs! swarm-id)
  (let [filename (format "%s-i%d-r%d.json" worker-id iteration round)]
    (write-json! (str (reviews-dir swarm-id) "/" filename)
                 {:worker-id worker-id
                  :iteration iteration
                  :round round
                  :verdict (name verdict)
                  :timestamp (str (java.time.Instant/now))
                  :output output
                  :diff-files (vec diff-files)})))

;; =============================================================================
;; Iteration Log — written per iteration for real-time dashboard visibility
;; =============================================================================

(defn write-iteration-log!
  "Write an iteration event log for a worker.
   Contains: outcome, timing, task info, metrics snapshot.
   Written at iteration end so dashboards can track progress in real-time."
  [swarm-id worker-id iteration
   {:keys [outcome duration-ms task-id recycled-tasks
           error-snippet review-rounds metrics]}]
  (when swarm-id
    (let [filename (format "%s-i%d.json" worker-id iteration)]
      (write-json! (str (iterations-dir swarm-id) "/" filename)
                   {:worker-id worker-id
                    :iteration iteration
                    :outcome (name outcome)
                    :timestamp (str (java.time.Instant/now))
                    :duration-ms duration-ms
                    :task-id task-id
                    :recycled-tasks (or recycled-tasks [])
                    :error-snippet error-snippet
                    :review-rounds (or review-rounds 0)
                    :metrics metrics}))))

;; =============================================================================
;; Live Summary — written after each iteration for mid-run visibility
;; =============================================================================

(def ^:private live-metrics
  "Atom holding per-worker metrics for live summary writes.
   Updated by workers after each iteration."
  (atom {}))

;; Serializes live-summary.json writes so concurrent workers don't
;; corrupt the file. The atom is already thread-safe, but the
;; read-atom → write-file sequence needs to be atomic too.
(def ^:private live-summary-lock (Object.))

(defn update-live-metrics!
  "Update live metrics for a worker. Called after each iteration."
  [worker-id metrics-map]
  (swap! live-metrics assoc worker-id metrics-map))

(defn write-live-summary!
  "Write a live summary snapshot to runs/{swarm-id}/live-summary.json.
   Called after each iteration so dashboards can show mid-run stats.
   Serialized via live-summary-lock to prevent concurrent file corruption."
  [swarm-id]
  (when swarm-id
    (locking live-summary-lock
      (let [workers @live-metrics]
        (write-json! (str (run-dir swarm-id) "/live-summary.json")
                     {:swarm-id swarm-id
                      :updated-at (str (java.time.Instant/now))
                      :workers workers})))))

;; =============================================================================
;; Swarm Summary — written at swarm end
;; =============================================================================

(defn write-summary!
  "Write the final swarm summary after all workers complete.
   Contains: per-worker stats and aggregate metrics."
  [swarm-id worker-results]
  (let [total-completed (reduce + 0 (map :completed worker-results))
        total-iterations (reduce + 0 (map :iterations worker-results))
        statuses (frequencies (map #(name (:status %)) worker-results))
        per-worker (mapv (fn [w]
                           {:id (:id w)
                            :harness (name (:harness w))
                            :model (:model w)
                            :status (name (:status w))
                            :completed (:completed w)
                            :iterations (:iterations w)
                            :merges (or (:merges w) 0)
                            :claims (or (:claims w) 0)
                            :rejections (or (:rejections w) 0)
                            :errors (or (:errors w) 0)
                            :recycled (or (:recycled w) 0)
                            :review-rounds-total (or (:review-rounds-total w) 0)})
                         worker-results)]
    (write-json! (str (run-dir swarm-id) "/summary.json")
                 {:swarm-id swarm-id
                  :finished-at (str (java.time.Instant/now))
                  :total-workers (count worker-results)
                  :total-completed total-completed
                  :total-iterations total-iterations
                  :status-counts statuses
                  :workers per-worker})
    ;; Clean up live-summary.json — its lifecycle ends when summary.json is written.
    ;; Leaving it around causes claude-web-view to show ghost "running" status.
    (let [live-file (io/file (str (run-dir swarm-id) "/live-summary.json"))]
      (when (.exists live-file)
        (.delete live-file)))
    (reset! live-metrics {})))

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

(defn read-run-log
  "Read run.json for a swarm."
  [swarm-id]
  (let [f (io/file (str (run-dir swarm-id) "/run.json"))]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

(defn read-summary
  "Read summary.json for a swarm."
  [swarm-id]
  (let [f (io/file (str (run-dir swarm-id) "/summary.json"))]
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

(defn list-iterations
  "List all iteration logs for a swarm, sorted by filename."
  [swarm-id]
  (let [d (io/file (iterations-dir swarm-id))]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".json"))
           (sort-by #(.getName %))
           (mapv (fn [f] (json/parse-string (slurp f) true)))))))

(defn read-live-summary
  "Read live-summary.json for a swarm (available during run)."
  [swarm-id]
  (let [f (io/file (str (run-dir swarm-id) "/live-summary.json"))]
    (when (.exists f)
      (json/parse-string (slurp f) true))))
