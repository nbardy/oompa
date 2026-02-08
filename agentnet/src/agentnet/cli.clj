(ns agentnet.cli
  "Command-line interface for AgentNet orchestrator.

   Usage:
     ./swarm.bb run                                    # Run all tasks once
     ./swarm.bb run --workers 4                        # With 4 parallel workers
     ./swarm.bb loop 20 --harness claude               # 20 iterations with Claude
     ./swarm.bb loop --workers claude:5 codex:4 --iterations 20  # Mixed harnesses
     ./swarm.bb swarm oompa.json                       # Multi-model from config
     ./swarm.bb prompt \"...\"                          # Ad-hoc task
     ./swarm.bb status                                 # Show last run
     ./swarm.bb worktrees                              # List worktree status
     ./swarm.bb cleanup                                # Remove all worktrees"
  (:require [agentnet.orchestrator :as orchestrator]
            [agentnet.worktree :as worktree]
            [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.agent :as agent]
            [babashka.process :as process]
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

(defn- make-swarm-id
  "Generate a short run-level swarm ID."
  []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn- parse-worker-spec
  "Parse 'harness:count' into {:harness :claude, :count 5}.
   Throws on invalid format."
  [s]
  (let [[harness count-str] (str/split s #":" 2)
        h (keyword harness)
        cnt (parse-int count-str 0)]
    (when-not (#{:codex :claude} h)
      (throw (ex-info (str "Unknown harness in worker spec: " s ". Use 'codex:N' or 'claude:N'") {})))
    (when (zero? cnt)
      (throw (ex-info (str "Invalid count in worker spec: " s ". Use format 'harness:count'") {})))
    {:harness h :count cnt}))

(defn- worker-spec? [s]
  "Check if string looks like 'harness:count' format"
  (and (string? s)
       (not (str/starts-with? s "--"))
       (str/includes? s ":")
       (re-matches #"[a-z]+:\d+" s)))

(defn- collect-worker-specs
  "Collect consecutive worker specs from args. Returns [specs remaining-args]."
  [args]
  (loop [specs []
         remaining args]
    (if-let [arg (first remaining)]
      (if (worker-spec? arg)
        (recur (conj specs (parse-worker-spec arg)) (next remaining))
        [specs remaining])
      [specs remaining])))

(defn parse-args [args]
  (loop [opts {:workers 2
               :harness :codex
               :model nil
               :dry-run false
               :iterations 1
               :worker-specs nil}
         remaining args]
    (if-let [arg (first remaining)]
      (cond
        ;; --workers can take either N or harness:count specs
        (= arg "--workers")
        (let [next-arg (second remaining)]
          (if (worker-spec? next-arg)
            ;; Collect all worker specs: --workers claude:5 codex:4
            (let [[specs rest] (collect-worker-specs (next remaining))]
              (recur (assoc opts :worker-specs specs) rest))
            ;; Simple count: --workers 4
            (recur (assoc opts :workers (parse-int next-arg 2))
                   (nnext remaining))))

        (= arg "--iterations")
        (recur (assoc opts :iterations (parse-int (second remaining) 1))
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

(declare cmd-swarm parse-model-string)

(defn- probe-model
  "Send 'say ok' to a model via its harness CLI. Returns true if model responds."
  [harness model]
  (try
    (let [cmd (case harness
                :claude ["claude" "--model" model "-p" "say ok"]
                :codex  ["codex" "exec" "--dangerously-bypass-approvals-and-sandbox" "--skip-git-repo-check" "--model" model "--" "say ok"])
          result (process/sh cmd {:out :string :err :string :timeout 30000})]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- validate-models!
  "Probe each unique harness:model pair. Prints results and exits if any fail."
  [worker-configs review-model]
  (let [models (cond-> (set (map (fn [wc]
                                   (parse-model-string (:model wc)))
                                 worker-configs))
                 review-model (conj review-model))
        _ (println "Validating models...")
        results (pmap (fn [{:keys [harness model]}]
                        (let [ok (probe-model harness model)]
                          (println (format "  %s:%s %s"
                                           (name harness) model
                                           (if ok "OK" "FAIL")))
                          {:harness harness :model model :ok ok}))
                      models)
        failures (filter (complement :ok) results)]
    (when (seq failures)
      (println)
      (println "ERROR: The following models are not accessible:")
      (doseq [{:keys [harness model]} failures]
        (println (format "  %s:%s" (name harness) model)))
      (println)
      (println "Fix model names in oompa.json and retry.")
      (System/exit 1))
    (println)))

(defn cmd-run
  "Run orchestrator — uses oompa.json if present, otherwise simple mode"
  [opts args]
  (if (.exists (io/file "oompa.json"))
    (cmd-swarm opts (or (seq args) ["oompa.json"]))
    (let [swarm-id (make-swarm-id)]
      (if-let [specs (:worker-specs opts)]
        ;; Mixed worker specs: --workers claude:5 codex:4
        (let [workers (mapcat
                        (fn [spec]
                          (let [{:keys [harness count]} spec]
                            (map-indexed
                              (fn [idx _]
                                (worker/create-worker
                                  {:id (format "%s-%d" (name harness) idx)
                                   :swarm-id swarm-id
                                   :harness harness
                                   :model (:model opts)
                                   :iterations 1}))
                              (range count))))
                        specs)]
          (println (format "Running once with mixed workers (swarm %s):" swarm-id))
          (doseq [spec specs]
            (println (format "  %dx %s" (:count spec) (name (:harness spec)))))
          (println)
          (worker/run-workers! workers))
        ;; Simple mode
        (do
          (println (format "Swarm ID: %s" swarm-id))
          (orchestrator/run-once! (assoc opts :swarm-id swarm-id)))))))

(defn cmd-loop
  "Run orchestrator N times"
  [opts args]
  (let [swarm-id (make-swarm-id)
        iterations (or (some-> (first args) (parse-int nil))
                       (:iterations opts)
                       20)]
    (if-let [specs (:worker-specs opts)]
      ;; Mixed worker specs: --workers claude:5 codex:4
      (let [workers (mapcat
                      (fn [spec]
                        (let [{:keys [harness count]} spec]
                          (map-indexed
                            (fn [idx _]
                              (worker/create-worker
                                {:id (format "%s-%d" (name harness) idx)
                                 :swarm-id swarm-id
                                 :harness harness
                                 :model (:model opts)
                                 :iterations iterations}))
                            (range count))))
                      specs)]
        (println (format "Starting %d iterations with mixed workers (swarm %s):" iterations swarm-id))
        (doseq [spec specs]
          (println (format "  %dx %s" (:count spec) (name (:harness spec)))))
        (println)
        (worker/run-workers! workers))
      ;; Simple mode: --workers N --harness X
      (let [model-str (if (:model opts)
                        (format " (model: %s)" (:model opts))
                        "")]
        (println (format "Starting %d iterations with %s harness%s..."
                         iterations (name (:harness opts)) model-str))
        (println (format "Swarm ID: %s" swarm-id))
        (orchestrator/run-loop! iterations (assoc opts :swarm-id swarm-id))))))

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
  "Parse model string into {:harness :model :reasoning}.
   Formats: 'harness:model', 'harness:model:reasoning', or just 'model'."
  [s]
  (if (and s (str/includes? s ":"))
    (let [parts (str/split s #":" 3)]
      (case (count parts)
        2 {:harness (keyword (first parts)) :model (second parts)}
        3 {:harness (keyword (first parts)) :model (second parts) :reasoning (nth parts 2)}
        {:harness :codex :model s}))
    {:harness :codex :model s}))

(defn cmd-swarm
  "Run multiple worker configs from oompa.json in parallel"
  [opts args]
  (let [config-file (or (first args) "oompa.json")
        f (io/file config-file)
        swarm-id (make-swarm-id)]
    (when-not (.exists f)
      (println (format "Config file not found: %s" config-file))
      (println)
      (println "Create oompa.json with format:")
      (println "{")
      (println "  \"workers\": [")
      (println "    {\"model\": \"codex:gpt-5.3-codex:medium\", \"prompt\": \"prompts/executor.md\", \"iterations\": 10, \"count\": 3, \"can_plan\": false},")
      (println "    {\"model\": \"claude:opus\", \"prompt\": [\"prompts/base.md\", \"prompts/planner.md\"], \"count\": 1}")
      (println "  ]")
      (println "}")
      (println)
      (println "prompt: string or array of paths — concatenated into one prompt.")
      (System/exit 1))
    (let [config (json/parse-string (slurp f) true)
          review-model (some-> (:review_model config) parse-model-string)
          worker-configs (:workers config)

          ;; Expand worker configs by count
          expanded-workers (mapcat (fn [wc]
                                     (let [cnt (or (:count wc) 1)]
                                       (repeat cnt (dissoc wc :count))))
                                   worker-configs)

          ;; Convert to worker format
          workers (map-indexed
                    (fn [idx wc]
                      (let [{:keys [harness model reasoning]} (parse-model-string (:model wc))]
                        (worker/create-worker
                          {:id (str "w" idx)
                           :swarm-id swarm-id
                           :harness harness
                           :model model
                           :reasoning reasoning
                           :iterations (or (:iterations wc) 10)
                           :prompts (:prompt wc)
                           :can-plan (:can_plan wc)
                           :review-harness (:harness review-model)
                           :review-model (:model review-model)})))
                    expanded-workers)]

      (println (format "Swarm config from %s:" config-file))
      (println (format "  Swarm ID: %s" swarm-id))
      (when review-model
        (println (format "  Review: %s:%s" (name (:harness review-model)) (:model review-model))))
      (println (format "  Workers: %d total" (count workers)))
      (doseq [[idx wc] (map-indexed vector worker-configs)]
        (let [{:keys [harness model]} (parse-model-string (:model wc))]
          (println (format "    - %dx %s:%s (%d iters%s)"
                           (or (:count wc) 1)
                           (name harness)
                           model
                           (or (:iterations wc) 10)
                           (if (:prompt wc) (str ", " (:prompt wc)) "")))))
      (println)

      ;; Preflight: probe each unique model before launching workers
      (validate-models! worker-configs review-model)

      ;; Run workers using new worker module
      (worker/run-workers! workers))))

(defn cmd-tasks
  "Show task status"
  [opts args]
  (tasks/ensure-dirs!)
  (let [status (tasks/status-summary)]
    (println "Task Status:")
    (println (format "  Pending:  %d" (:pending status)))
    (println (format "  Current:  %d" (:current status)))
    (println (format "  Complete: %d" (:complete status)))
    (println)
    (when (pos? (:pending status))
      (println "Pending tasks:")
      (doseq [t (tasks/list-pending)]
        (println (format "  - %s: %s" (:id t) (:summary t)))))
    (when (pos? (:current status))
      (println "In-progress tasks:")
      (doseq [t (tasks/list-current)]
        (println (format "  - %s: %s" (:id t) (:summary t)))))))

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
  (println "  tasks            Show task status (pending/current/complete)")
  (println "  prompt \"...\"     Run ad-hoc prompt")
  (println "  status           Show last run summary")
  (println "  worktrees        List worktree status")
  (println "  cleanup          Remove all worktrees")
  (println "  context          Print context block")
  (println "  check            Check agent backends")
  (println "  help             Show this help")
  (println)
  (println "Options:")
  (println "  --workers N              Number of parallel workers (default: 2)")
  (println "  --workers H:N [H:N ...]  Mixed workers by harness (e.g., claude:5 codex:4)")
  (println "  --iterations N           Number of iterations per worker (default: 1)")
  (println "  --harness {codex,claude} Agent harness to use (default: codex)")
  (println "  --model MODEL            Model to use (e.g., codex-5.2, opus-4.5)")
  (println "  --dry-run                Skip actual merges")
  (println "  --keep-worktrees         Don't cleanup worktrees after run")
  (println)
  (println "Examples:")
  (println "  ./swarm.bb loop 10 --harness codex --model gpt-5.3-codex --workers 3")
  (println "  ./swarm.bb loop --workers claude:5 codex:4 --iterations 20")
  (println "  ./swarm.bb swarm oompa.json  # Run multi-model config"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(def commands
  {"run" cmd-run
   "loop" cmd-loop
   "swarm" cmd-swarm
   "tasks" cmd-tasks
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
