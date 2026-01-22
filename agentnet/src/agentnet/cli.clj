(ns agentnet.cli
  "Command-line interface for AgentNet orchestrator.

   Usage:
     ./agentnet.bb run              # Run all tasks once
     ./agentnet.bb run --workers 4  # With 4 parallel workers
     ./agentnet.bb run --claude     # Use Claude instead of Codex
     ./agentnet.bb run --dry-run    # Don't actually merge
     ./agentnet.bb loop 20          # Run 20 iterations
     ./agentnet.bb prompt \"...\"     # Ad-hoc task
     ./agentnet.bb status           # Show last run
     ./agentnet.bb worktrees        # List worktree status
     ./agentnet.bb cleanup          # Remove all worktrees"
  (:require [agentnet.orchestrator :as orchestrator]
            [agentnet.worktree :as worktree]
            [agentnet.agent :as agent]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-int [s default]
  (try
    (Integer/parseInt s)
    (catch Exception _ default)))

(defn parse-args [args]
  (loop [opts {:workers 2
               :harness :codex
               :model nil
               :dry-run false
               :iterations 1}
         remaining args]
    (if-let [arg (first remaining)]
      (cond
        (= arg "--workers")
        (recur (assoc opts :workers (parse-int (second remaining) 2))
               (nnext remaining))

        (= arg "--harness")
        (let [h (keyword (second remaining))]
          (when-not (#{:codex :claude} h)
            (throw (ex-info (str "Unknown harness: " (second remaining) ". Use 'codex' or 'claude'") {})))
          (recur (assoc opts :harness h)
                 (nnext remaining)))

        (= arg "--model")
        (recur (assoc opts :model (second remaining))
               (nnext remaining))

        ;; Legacy flags (still supported)
        (= arg "--claude")
        (recur (assoc opts :harness :claude)
               (next remaining))

        (= arg "--codex")
        (recur (assoc opts :harness :codex)
               (next remaining))

        (= arg "--dry-run")
        (recur (assoc opts :dry-run true)
               (next remaining))

        (= arg "--keep-worktrees")
        (recur (assoc opts :keep-worktrees true)
               (next remaining))

        (= arg "--")
        {:opts opts :args (vec (next remaining))}

        (str/starts-with? arg "--")
        (throw (ex-info (str "Unknown option: " arg) {:arg arg}))

        :else
        {:opts opts :args (vec remaining)})
      {:opts opts :args []})))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-run
  "Run orchestrator once"
  [opts args]
  (orchestrator/run-once! opts))

(defn cmd-loop
  "Run orchestrator N times"
  [opts args]
  (let [iterations (parse-int (first args) 20)
        model-str (if (:model opts)
                    (format " (model: %s)" (:model opts))
                    "")]
    (println (format "Starting %d iterations with %s harness%s..."
                     iterations (name (:harness opts)) model-str))
    (orchestrator/run-loop! iterations opts)))

(defn cmd-prompt
  "Run ad-hoc prompt as single task"
  [opts args]
  (let [prompt-text (str/join " " args)]
    (when (str/blank? prompt-text)
      (println "Error: prompt text required")
      (System/exit 1))
    ;; Create temporary task
    (let [task {:id (format "prompt-%d" (System/currentTimeMillis))
                :summary prompt-text
                :targets ["src" "tests" "docs"]
                :priority 1}]
      ;; Write to temporary tasks file
      (spit "config/tasks.edn" (pr-str [task]))
      ;; Run
      (orchestrator/run-once! opts))))

(defn cmd-status
  "Show status of last run"
  [opts args]
  (let [runs-dir (io/file "runs")
        files (when (.exists runs-dir)
                (->> (.listFiles runs-dir)
                     (filter #(.isFile %))
                     (sort-by #(.lastModified %) >)))]
    (if-let [latest (first files)]
      (do
        (println (format "Latest run: %s" (.getName latest)))
        (println)
        (with-open [r (io/reader latest)]
          (let [entries (mapv #(json/parse-string % true) (line-seq r))
                by-status (group-by :status entries)]
            (doseq [[status tasks] (sort-by first by-status)]
              (println (format "%s: %d" (name status) (count tasks))))
            (println)
            (println (format "Total: %d tasks" (count entries))))))
      (println "No runs found."))))

(defn cmd-worktrees
  "List worktree status"
  [opts args]
  (let [state-file (io/file ".workers/state.edn")]
    (if (.exists state-file)
      (let [pool (read-string (slurp state-file))
            pool' (worktree/list-worktrees pool)]
        (println "Worktrees:")
        (doseq [{:keys [id path status current-task]} pool']
          (println (format "  %s: %s%s"
                           id
                           (name status)
                           (if current-task
                             (str " [" current-task "]")
                             "")))))
      (println "No worktrees initialized."))))

(defn cmd-cleanup
  "Remove all worktrees"
  [opts args]
  (let [state-file (io/file ".workers/state.edn")]
    (if (.exists state-file)
      (let [pool (read-string (slurp state-file))]
        (println "Removing worktrees...")
        (worktree/cleanup-pool! pool)
        (println "Done."))
      (println "No worktrees to clean up."))))

(defn cmd-context
  "Print current context (for debugging prompts)"
  [opts args]
  (let [ctx (orchestrator/build-context [])]
    (println (:context_header ctx))))

(defn cmd-check
  "Check if agent backends are available"
  [opts args]
  (println "Checking agent backends...")
  (doseq [agent-type [:codex :claude]]
    (let [available? (agent/check-available agent-type)]
      (println (format "  %s: %s"
                       (name agent-type)
                       (if available? "✓ available" "✗ not found"))))))

(defn- parse-model-string
  "Parse 'harness:model' string into {:harness :model}"
  [s]
  (if (and s (str/includes? s ":"))
    (let [[h m] (str/split s #":" 2)]
      {:harness (keyword h) :model m})
    {:harness :codex :model s}))

(defn cmd-swarm
  "Run multiple worker configs from oompa.json in parallel"
  [opts args]
  (let [config-file (or (first args) "oompa.json")
        f (io/file config-file)]
    (when-not (.exists f)
      (println (format "Config file not found: %s" config-file))
      (println)
      (println "Create oompa.json with format:")
      (println "{")
      (println "  \"plan_model\": \"claude:opus-4.5\",")
      (println "  \"review_model\": \"codex:codex-5.2\",")
      (println "  \"workers\": [")
      (println "    {\"model\": \"codex:codex-5.2-mini\", \"iterations\": 10, \"count\": 3},")
      (println "    {\"model\": \"codex:codex-5.2\", \"iterations\": 5, \"count\": 1, \"prompt\": \"prompts/senior.md\"}")
      (println "  ]")
      (println "}")
      (System/exit 1))
    (let [config (json/parse-string (slurp f) true)
          plan-model (some-> (:plan_model config) parse-model-string)
          review-model (some-> (:review_model config) parse-model-string)
          worker-configs (:workers config)
          ;; Expand worker configs by count
          expanded-workers (mapcat (fn [wc]
                                     (let [cnt (or (:count wc) 1)]
                                       (repeat cnt (dissoc wc :count))))
                                   worker-configs)
          total-workers (count expanded-workers)]

      (println (format "Swarm config from %s:" config-file))
      (when plan-model
        (println (format "  Plan:   %s:%s" (name (:harness plan-model)) (:model plan-model))))
      (when review-model
        (println (format "  Review: %s:%s" (name (:harness review-model)) (:model review-model))))
      (println (format "  Workers: %d total" total-workers))
      (doseq [[idx wc] (map-indexed vector worker-configs)]
        (let [{:keys [harness model]} (parse-model-string (:model wc))]
          (println (format "    - %dx %s:%s (%d iters%s)"
                           (or (:count wc) 1)
                           (name harness)
                           model
                           (or (:iterations wc) 10)
                           (if (:prompt wc) (str ", " (:prompt wc)) "")))))
      (println)

      ;; Launch each expanded worker in parallel
      (let [futures (doall
                      (map-indexed
                        (fn [idx wc]
                          (let [{:keys [harness model]} (parse-model-string (:model wc))
                                iters (or (:iterations wc) 10)
                                custom-prompt (:prompt wc)]
                            (println (format "[%d] Starting %s:%s..." idx (name harness) model))
                            (future
                              (try
                                (orchestrator/run-loop! iters
                                                        (merge opts
                                                               {:harness harness
                                                                :model model
                                                                :review-harness (:harness review-model)
                                                                :review-model (:model review-model)
                                                                :custom-prompt custom-prompt
                                                                :workers 1}))
                                (catch Exception e
                                  (println (format "[%d] Error: %s" idx (.getMessage e))))))))
                        expanded-workers))]
        (println)
        (println "All workers launched. Waiting for completion...")
        ;; Wait for all futures
        (doseq [[idx f] (map-indexed vector futures)]
          (deref f)
          (println (format "[%d] completed" idx)))
        (println)
        (println "Swarm complete.")))))

(defn cmd-help
  "Print usage information"
  [opts args]
  (println "AgentNet Orchestrator")
  (println)
  (println "Usage: ./swarm.bb <command> [options]")
  (println)
  (println "Commands:")
  (println "  run              Run all tasks once")
  (println "  loop N           Run N iterations")
  (println "  swarm [file]     Run multiple worker configs from oompa.json (parallel)")
  (println "  prompt \"...\"     Run ad-hoc prompt")
  (println "  status           Show last run summary")
  (println "  worktrees        List worktree status")
  (println "  cleanup          Remove all worktrees")
  (println "  context          Print context block")
  (println "  check            Check agent backends")
  (println "  help             Show this help")
  (println)
  (println "Options:")
  (println "  --workers N          Number of parallel workers (default: 2)")
  (println "  --harness {codex,claude}  Agent harness to use (default: codex)")
  (println "  --model MODEL        Model to use (e.g., codex-5.2, codex-5.2-mini, opus)")
  (println "  --dry-run            Skip actual merges")
  (println "  --keep-worktrees     Don't cleanup worktrees after run")
  (println)
  (println "Examples:")
  (println "  ./swarm.bb loop 10 --harness codex --model codex-5.2-mini --workers 3")
  (println "  ./swarm.bb loop 5 --harness claude --model opus --workers 2")
  (println "  ./swarm.bb swarm oompa.json  # Run multi-model config"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(def commands
  {"run" cmd-run
   "loop" cmd-loop
   "swarm" cmd-swarm
   "prompt" cmd-prompt
   "status" cmd-status
   "worktrees" cmd-worktrees
   "cleanup" cmd-cleanup
   "context" cmd-context
   "check" cmd-check
   "help" cmd-help})

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (if-let [handler (get commands cmd)]
      (let [{:keys [opts args]} (parse-args rest-args)]
        (try
          (handler opts args)
          (catch Exception e
            (binding [*out* *err*]
              (println (format "Error: %s" (.getMessage e))))
            (System/exit 1))))
      (do
        (cmd-help {} [])
        (when cmd
          (println)
          (println (format "Unknown command: %s" cmd)))
        (System/exit (if cmd 1 0))))))
