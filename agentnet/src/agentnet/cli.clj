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
               :agent-type :codex
               :dry-run false
               :iterations 1}
         remaining args]
    (if-let [arg (first remaining)]
      (cond
        (= arg "--workers")
        (recur (assoc opts :workers (parse-int (second remaining) 2))
               (nnext remaining))

        (= arg "--claude")
        (recur (assoc opts :agent-type :claude)
               (next remaining))

        (= arg "--codex")
        (recur (assoc opts :agent-type :codex)
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
  (let [iterations (parse-int (first args) 20)]
    (println (format "Starting %d iterations with %s backend..."
                     iterations (name (:agent-type opts))))
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

(defn cmd-help
  "Print usage information"
  [opts args]
  (println "AgentNet Orchestrator")
  (println)
  (println "Usage: ./agentnet.bb <command> [options]")
  (println)
  (println "Commands:")
  (println "  run              Run all tasks once")
  (println "  loop N           Run N iterations")
  (println "  prompt \"...\"     Run ad-hoc prompt")
  (println "  status           Show last run summary")
  (println "  worktrees        List worktree status")
  (println "  cleanup          Remove all worktrees")
  (println "  context          Print context block")
  (println "  check            Check agent backends")
  (println "  help             Show this help")
  (println)
  (println "Options:")
  (println "  --workers N      Number of parallel workers (default: 2)")
  (println "  --claude         Use Claude backend")
  (println "  --codex          Use Codex backend (default)")
  (println "  --dry-run        Skip actual merges")
  (println "  --keep-worktrees Don't cleanup worktrees after run"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(def commands
  {"run" cmd-run
   "loop" cmd-loop
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
