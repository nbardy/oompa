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

(defn cmd-swarm
  "Run multiple worker configs from oompa.json in parallel"
  [opts args]
  (let [config-file (or (first args) "oompa.json")
        f (io/file config-file)]
    (when-not (.exists f)
      (println (format "Config file not found: %s" config-file))
      (println)
      (println "Create oompa.json with format:")
      (println "  {")
      (println "    \"workers\": [")
      (println "      {\"harness\": \"codex\", \"model\": \"codex-5.2\", \"iterations\": 10},")
      (println "      {\"harness\": \"codex\", \"model\": \"codex-5.2-mini\", \"iterations\": 10},")
      (println "      {\"harness\": \"claude\", \"model\": \"opus\", \"iterations\": 5}")
      (println "    ]")
      (println "  }")
      (System/exit 1))
    (let [config (json/parse-string (slurp f) true)
          worker-configs (:workers config)]
      (println (format "Launching %d worker configs from %s..." (count worker-configs) config-file))
      (println)
      ;; Launch each worker config in parallel using futures
      (let [futures (doall
                      (map-indexed
                        (fn [idx {:keys [harness model iterations] :as wc}]
                          (let [harness-kw (keyword (or harness "codex"))
                                iters (or iterations 10)]
                            (println (format "  [%d] %s/%s x%d iterations"
                                             idx (name harness-kw) (or model "default") iters))
                            (future
                              (try
                                (orchestrator/run-loop! iters
                                                        (merge opts
                                                               {:harness harness-kw
                                                                :model model
                                                                :workers 1}))
                                (catch Exception e
                                  (println (format "[%d] Error: %s" idx (.getMessage e))))))))
                        worker-configs))]
        (println)
        (println "All workers launched. Waiting for completion...")
        ;; Wait for all futures
        (doseq [[idx f] (map-indexed vector futures)]
          (deref f)
          (println (format "  [%d] completed" idx)))
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
