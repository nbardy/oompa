(ns agentnet.cli
  "Command-line interface for AgentNet orchestrator.

   Usage:
     ./swarm.bb run                                    # Run swarm from config (oompa.json)
     ./swarm.bb run --detach --config oompa.json       # Run in background with startup validation
     ./swarm.bb loop 20 --harness claude               # 20 iterations with Claude
     ./swarm.bb loop --workers claude:5 opencode:2 --iterations 20  # Mixed harnesses
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
            [agentnet.harness :as harness]
            [agentnet.runs :as runs]
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

(def ^:private harnesses (harness/known-harnesses))

(defn- make-swarm-id
  "Generate a short run-level swarm ID."
  []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn- parse-worker-spec
  "Parse 'harness:count' into {:harness :opencode, :count 5}.
   Throws on invalid format."
  [s]
  (let [[harness count-str] (str/split s #":" 2)
        h (keyword harness)
        cnt (parse-int count-str 0)]
    (when-not (harnesses h)
      (throw (ex-info (str "Unknown harness in worker spec: " s ". Known: " (str/join ", " (map name (sort harnesses)))) {})))
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
               :detach false
               :all false
               :config-file nil
               :startup-timeout nil
               :iterations 1
               :worker-specs nil}
         remaining args]
    (if-let [arg (first remaining)]
      (cond
        ;; --workers can take either N or harness:count specs
        (= arg "--workers")
        (let [next-arg (second remaining)]
          (if (worker-spec? next-arg)
            ;; Collect all worker specs: --workers claude:5 opencode:2
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
          (when-not (harnesses h)
            (throw (ex-info (str "Unknown harness: " (second remaining) ". Known: " (str/join ", " (map name (sort harnesses)))) {})))
          (recur (assoc opts :harness h)
                 (nnext remaining)))

        (= arg "--model")
        (recur (assoc opts :model (second remaining))
               (nnext remaining))

        (= arg "--config")
        (let [config-file (second remaining)]
          (when (str/blank? config-file)
            (throw (ex-info "--config requires a path" {:arg arg})))
          (recur (assoc opts :config-file config-file)
                 (nnext remaining)))

        (or (= arg "--detach") (= arg "--dettach"))
        (recur (assoc opts :detach true)
               (next remaining))

        (= arg "--all")
        (recur (assoc opts :all true)
               (next remaining))

        (= arg "--startup-timeout")
        (let [seconds (parse-int (second remaining) nil)]
          (when-not (and (number? seconds) (pos? seconds))
            (throw (ex-info "--startup-timeout requires a positive integer (seconds)" {:arg arg})))
          (recur (assoc opts :startup-timeout seconds)
                 (nnext remaining)))

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

(declare cmd-swarm parse-model-string pid-alive?)

(defn- check-git-clean!
  "Abort if git working tree is dirty. Dirty index causes merge conflicts
   and wasted worker iterations."
  []
  (let [result (process/sh ["git" "status" "--porcelain"]
                           {:out :string :err :string})
        output (str/trim (:out result))]
    (when (and (zero? (:exit result)) (not (str/blank? output)))
      (println "ERROR: Git working tree is dirty. Resolve before running swarm.")
      (println)
      (println output)
      (println)
      (println "Run 'git stash' or 'git commit' first.")
      (System/exit 1))))

(defn- check-stale-worktrees!
  "Abort if stale oompa worktrees or branches exist from a prior run.
   Corrupted .git/worktrees/ entries poison git worktree add for ALL workers,
   not just the worker whose entry is stale. (See swarm af32b180 — kimi-k2.5
   w9 went 20/20 doing nothing because w10's corrupt commondir blocked it.)"
  []
  ;; Prune orphaned metadata first — cleans entries whose directories are gone
  (let [prune-result (process/sh ["git" "worktree" "prune"] {:out :string :err :string})]
    (when-not (zero? (:exit prune-result))
      (println "WARNING: git worktree prune failed:")
      (println (:err prune-result))))
  (let [;; Find .ww* directories (oompa per-iteration worktree naming convention)
        ls-result (process/sh ["find" "." "-maxdepth" "1" "-type" "d" "-name" ".ww*"]
                              {:out :string})
        stale-dirs (when (zero? (:exit ls-result))
                     (->> (str/split-lines (:out ls-result))
                          (remove str/blank?)))
        ;; Find oompa/* branches
        br-result (process/sh ["git" "branch" "--list" "oompa/*"]
                              {:out :string})
        stale-branches (when (zero? (:exit br-result))
                         (->> (str/split-lines (:out br-result))
                              (map str/trim)
                              (remove str/blank?)))]
    (when (or (seq stale-dirs) (seq stale-branches))
      (println "ERROR: Stale oompa worktrees detected from a prior run.")
      (println "       Corrupt worktree metadata will cause worker failures.")
      (println)
      (when (seq stale-dirs)
        (println (format "  Stale directories (%d):" (count stale-dirs)))
        (doseq [d stale-dirs] (println (str "    " d))))
      (when (seq stale-branches)
        (println (format "  Stale branches (%d):" (count stale-branches)))
        (doseq [b stale-branches] (println (str "    " b))))
      (println)
      (println "Clean up with:")
      (println "  git worktree prune; for d in .ww*/; do git worktree remove --force \"$d\" 2>/dev/null; done; git branch --list 'oompa/*' | xargs git branch -D 2>/dev/null; rm -rf .ww*")
      (println)
      (System/exit 1))))

(defn- probe-model
  "Send 'say ok' to a model via its harness CLI. Returns true if model responds.
   Uses harness/build-probe-cmd for the command, /dev/null stdin to prevent hang."
  [harness-kw model]
  (try
    (let [cmd (harness/build-probe-cmd harness-kw model)
          null-in (io/input-stream (io/file "/dev/null"))
          proc (process/process cmd {:out :string :err :string :in null-in})
          result (deref proc 30000 :timeout)]
      (if (= result :timeout)
        (do (.destroyForcibly (:proc proc)) false)
        (zero? (:exit result))))
    (catch Exception _ false)))

(defn- validate-models!
  "Probe each unique harness:model pair. Prints results and exits if any fail."
  [worker-configs review-model]
  (let [;; Deduplicate by harness:model only (ignore reasoning level)
        models (cond-> (->> worker-configs
                            (map (fn [wc]
                                   (let [{:keys [harness model]} (parse-model-string (:model wc))]
                                     {:harness harness :model model})))
                            set)
                 review-model (conj (select-keys review-model [:harness :model])))
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

(def ^:private default-detach-startup-timeout 20)

(defn- run-id []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn- run-ts []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
           (java.time.LocalDateTime/now)))

(defn- default-config-file
  []
  (cond
    (.exists (io/file "oompa.json")) "oompa.json"
    (.exists (io/file "oompa/oompa.json")) "oompa/oompa.json"
    :else nil))

(defn- resolve-config-file
  [opts args]
  (let [candidate (or (:config-file opts)
                      (first args)
                      (default-config-file))]
    (when candidate
      (.getCanonicalPath (io/file candidate)))))

(defn- prepare-log-file!
  "Create oompa/logs and return absolute log path."
  [rid]
  (let [dir (if (.exists (io/file "oompa"))
              (io/file "oompa" "logs")
              (io/file "runs" "logs"))]
    (.mkdirs dir)
    (.getCanonicalPath (io/file dir (str (run-ts) "_" rid ".log")))))

(defn- read-file-safe
  [path]
  (try
    (if (.exists (io/file path))
      (slurp path)
      "")
    (catch Exception _
      "")))

(defn- tail-lines
  [text n]
  (->> (str/split-lines (or text ""))
       (take-last n)
       (str/join "\n")))

(defn- extract-swarm-id
  [text]
  (some->> text
           (re-find #"Swarm ID:\s*([0-9a-f]{8})")
           second))

(defn- startup-diagnostic-lines
  [text]
  (->> (str/split-lines (or text ""))
       (filter #(re-find #"ERROR:|FAIL|WARNING:" %))
       (take-last 20)))

(defn- print-preflight-warnings!
  []
  (let [agent-cli? (zero? (:exit (process/sh ["which" "agent-cli"]
                                              {:out :string :err :string})))]
    (when-not agent-cli?
      (println "WARNING: 'agent-cli' is not on PATH.")
      (println "         Model validation may report false model-access failures.")))
  (let [dirty (process/sh ["git" "status" "--porcelain"]
                          {:out :string :err :string})
        lines (->> (:out dirty)
                   str/split-lines
                   (remove str/blank?))]
    (when (seq lines)
      (println (format "WARNING: Git working tree is dirty (%d changed paths)." (count lines)))
      (println "         Swarm startup may fail until changes are committed/stashed.")
      (doseq [line (take 20 lines)]
        (println line))
      (when (> (count lines) 20)
        (println (format "... (%d total changed paths)" (count lines)))))))

(defn- runtime-classpath-entry
  "Best-effort classpath root for agentnet sources."
  []
  (or
    (some-> (System/getenv "OOMPA_PACKAGE_ROOT")
            (io/file "agentnet" "src")
            .getCanonicalPath)
    (->> (str/split (or (System/getProperty "java.class.path") "")
                    (re-pattern (java.io.File/pathSeparator)))
         (map str/trim)
         (remove str/blank?)
         (map io/file)
         (filter #(.exists %))
         (map #(.getCanonicalPath %))
         (some #(when (str/ends-with? % (str "agentnet" java.io.File/separator "src"))
                  %)))
    (.getCanonicalPath (io/file "agentnet" "src"))))

(defn- run-classpath
  []
  (runtime-classpath-entry))

(defn- run-script-path
  []
  (if-let [pkg-root (System/getenv "OOMPA_PACKAGE_ROOT")]
    (.getCanonicalPath (io/file pkg-root "swarm.bb"))
    (let [cp (io/file (runtime-classpath-entry))
          ;; cp = <repo>/agentnet/src -> <repo>/swarm.bb
          repo-root (some-> cp .getParentFile .getParentFile)
          candidate (when repo-root (io/file repo-root "swarm.bb"))]
      (if (and candidate (.exists candidate))
        (.getCanonicalPath candidate)
        (.getCanonicalPath (io/file "swarm.bb"))))))

(defn- detached-cmd
  [opts config-file]
  (cond-> ["bb" "--classpath" (run-classpath) (run-script-path) "swarm"]
    (:dry-run opts) (conj "--dry-run")
    true (conj config-file)))

(defn- shell-quote
  [s]
  (str "'" (str/replace (str s) "'" "'\"'\"'") "'"))

(defn- spawn-detached!
  [cmd log-file]
  (let [script (str "nohup "
                    (str/join " " (map shell-quote cmd))
                    " >> " (shell-quote log-file)
                    " 2>&1 < /dev/null & echo $!")
        result (process/sh ["bash" "-lc" script] {:out :string :err :string})
        pid-str (some-> (:out result) str/trim)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to spawn detached swarm process"
                      {:exit (:exit result) :err (:err result)})))
    (when-not (re-matches #"\d+" (or pid-str ""))
      (throw (ex-info "Detached spawn did not return a PID"
                      {:out (:out result) :err (:err result)})))
    (Long/parseLong pid-str)))

(defn- pid-alive?
  [pid]
  (zero? (:exit (process/sh ["kill" "-0" (str pid)]
                            {:out :string :err :string}))))

(defn- wait-for-startup!
  [pid log-file timeout-sec]
  (loop [waited 0]
    (let [content (read-file-safe log-file)
          started? (str/includes? content "Started event written to runs/")
          alive? (pid-alive? pid)]
      (cond
        started?
        {:status :started
         :content content
         :swarm-id (extract-swarm-id content)}

        (not alive?)
        {:status :failed
         :content content}

        (>= waited timeout-sec)
        {:status :timeout
         :content content}

        :else
        (do
          (Thread/sleep 1000)
          (recur (inc waited)))))))

(defn- cmd-run-detached
  [opts config-file]
  (print-preflight-warnings!)
  (when-not (.exists (io/file config-file))
    (println (format "Config not found: %s" config-file))
    (System/exit 1))
  (let [timeout-sec (or (:startup-timeout opts)
                        (parse-int (System/getenv "OOMPA_DETACH_STARTUP_TIMEOUT")
                                   default-detach-startup-timeout))
        rid (run-id)
        log-file (prepare-log-file! rid)
        cmd (detached-cmd opts config-file)
        pid (spawn-detached! cmd log-file)]
    (println (format "Config:      %s" config-file))
    (when (:dry-run opts)
      (println "Merge mode:  dry-run"))
    (let [{:keys [status content swarm-id]} (wait-for-startup! pid log-file timeout-sec)]
      (case status
        :failed
        (do
          (println)
          (println "ERROR: Detached swarm exited during startup validation.")
          (println "Startup log excerpt:")
          (println (tail-lines content 120))
          (System/exit 1))

        :timeout
        (do
          (println)
          (println (format "WARNING: Detached swarm still initializing after %ss." timeout-sec))
          (println "Recent startup log lines:")
          (println (tail-lines content 40)))

        nil)
      (let [diag (startup-diagnostic-lines content)]
        (when (seq diag)
          (println)
          (println "Startup diagnostics:")
          (doseq [line diag]
            (println line))))
      (println)
      (println "  ┌──────────────────────────────────────────────────────────────┐")
      (println "  │  OOMPA SWARM RUN (DETACHED)                               │")
      (println (format "  │  Run id:   %-46s│" rid))
      (println (format "  │  PID:      %-46s│" pid))
      (println (format "  │  Log file: %-46s│" log-file))
      (println (format "  │  Swarm ID: %-46s│" (or swarm-id "(pending)")))
      (println "  └──────────────────────────────────────────────────────────────┘")
      (println))))

(defn- cmd-run-legacy
  "Run orchestrator once from worker specs (legacy mode)."
  [opts args]
  (let [swarm-id (make-swarm-id)]
    (if-let [specs (:worker-specs opts)]
      ;; Mixed worker specs: --workers claude:5 opencode:2
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
      ;; Simple mode retired — use oompa.json or --workers harness:count
      (do
        (println "Simple mode is no longer supported. Use oompa.json or --workers harness:count.")
        (System/exit 1)))))

(defn cmd-run
  "Run swarm from config. Use --detach for background mode."
  [opts args]
  (if-let [config-file (resolve-config-file opts args)]
    (if (:detach opts)
      (cmd-run-detached opts config-file)
      (cmd-swarm opts [config-file]))
    (cmd-run-legacy opts args)))

(defn cmd-loop
  "Run orchestrator N times"
  [opts args]
  (let [swarm-id (make-swarm-id)
        iterations (or (some-> (first args) (parse-int nil))
                       (:iterations opts)
                       20)]
    (if-let [specs (:worker-specs opts)]
      ;; Mixed worker specs: --workers claude:5 opencode:2
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
      ;; Simple mode retired — use oompa.json or --workers harness:count
      (do
        (println "Simple mode is no longer supported. Use oompa.json or --workers harness:count.")
        (System/exit 1)))))

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
  "Show status of last run — reads event-sourced runs/{swarm-id}/ data."
  [opts args]
  (let [run-ids (runs/list-runs)]
    (if (seq run-ids)
      (let [swarm-id (or (first args) (first run-ids))
            started (runs/read-started swarm-id)
            stopped (runs/read-stopped swarm-id)
            cycles (runs/list-cycles swarm-id)
            reviews (runs/list-reviews swarm-id)]
        (println (format "Swarm: %s" swarm-id))
        (when started
          (println (format "  Started: %s" (:started-at started)))
          (println (format "  PID:     %s" (or (:pid started) "N/A")))
          (println (format "  Config:  %s" (or (:config-file started) "N/A")))
          (println (format "  Workers: %d" (count (:workers started)))))
        (println)
        (if stopped
          (println (format "Stopped: %s (reason: %s%s)"
                           (:stopped-at stopped)
                           (:reason stopped)
                           (if (:error stopped)
                             (str ", error: " (:error stopped))
                             "")))
          (println "  (still running — no stopped event yet)"))
        (when (seq cycles)
          (println)
          (println (format "Cycles: %d total" (count cycles)))
          (doseq [c cycles]
            (println (format "  %s-c%d: %s (%dms, claimed: %s)"
                             (:worker-id c) (:cycle c)
                             (:outcome c)
                             (or (:duration-ms c) 0)
                             (str/join ", " (or (:claimed-task-ids c) []))))))
        (when (seq reviews)
          (println)
          (println (format "Reviews: %d total" (count reviews)))
          (doseq [r reviews]
            (println (format "  %s-c%d-r%d: %s"
                             (:worker-id r) (:cycle r) (:round r)
                             (:verdict r))))))
      ;; Fall back to legacy JSONL format
      (let [runs-dir (io/file "runs")
            files (when (.exists runs-dir)
                    (->> (.listFiles runs-dir)
                         (filter #(.isFile %))
                         (sort-by #(.lastModified %) >)))]
        (if-let [latest (first files)]
          (do
            (println (format "Latest run (legacy): %s" (.getName latest)))
            (println)
            (with-open [r (io/reader latest)]
              (let [entries (mapv #(json/parse-string % true) (line-seq r))
                    by-status (group-by :status entries)]
                (doseq [[status tasks] (sort-by first by-status)]
                  (println (format "%s: %d" (name status) (count tasks))))
                (println)
                (println (format "Total: %d tasks" (count entries))))))
          (println "No runs found."))))))

(def ^:private error-outcomes
  #{"error" "merge-failed" "rejected" "stuck"})

(defn- run-state
  "Derive run lifecycle state from started/stopped events + PID liveness."
  [started stopped]
  (cond
    (nil? started) "missing-started"
    stopped (str "stopped/" (:reason stopped))
    (pid-alive? (:pid started)) "running"
    :else "stale"))

(defn- latest-cycles-by-worker
  "Return map of worker-id -> latest cycle entry."
  [cycles]
  (reduce (fn [acc c]
            (let [wid (:worker-id c)
                  prev (get acc wid)]
              (if (or (nil? prev)
                      (> (or (:cycle c) 0) (or (:cycle prev) 0)))
                (assoc acc wid c)
                acc)))
          {}
          cycles))

(defn- worker-runtime
  "Best-effort worker runtime classification for view output."
  [worker latest-cycle run-state*]
  (let [iter-max (or (:iterations worker) 0)
        iter-done (or (:cycle latest-cycle) 0)
        outcome (or (:outcome latest-cycle) "-")]
    (cond
      (>= iter-done iter-max) "exhausted"
      (str/starts-with? run-state* "stopped/") "stopped"
      (= run-state* "stale") "stale"
      (nil? latest-cycle) "starting"
      (= outcome "working") "working"
      (= outcome "executor-done") "idle"
      :else outcome)))

(defn- model-label
  [{:keys [harness model reasoning]}]
  (str harness ":" model (when reasoning (str ":" reasoning))))

(defn- run-metrics
  "Summarize cycle metrics for a run."
  [cycles]
  (let [merged (count (filter #(= "merged" (:outcome %)) cycles))
        failed (count (filter #(error-outcomes (:outcome %)) cycles))
        claimed-all (->> cycles
                         (mapcat #(or (:claimed-task-ids %) []))
                         (remove str/blank?))
        completed-ids (->> cycles
                           (filter #(= "merged" (:outcome %)))
                           (mapcat #(or (:claimed-task-ids %) []))
                           (remove str/blank?)
                           set)]
    {:merged merged
     :failed failed
     :claimed (count (set claimed-all))
     :completed (count completed-ids)}))

(defn- cmd-view-one
  [swarm-id]
  (if-let [started (runs/read-started swarm-id)]
    (let [stopped (runs/read-stopped swarm-id)
          cycles (or (runs/list-cycles swarm-id) [])
          reviews (or (runs/list-reviews swarm-id) [])
          workers (or (:workers started) [])
          run-state* (run-state started stopped)
          metrics (run-metrics cycles)
          latest-by-worker (latest-cycles-by-worker cycles)]
      (println (format "Swarm:   %s" swarm-id))
      (println (format "State:   %s" run-state*))
      (println (format "Started: %s" (:started-at started)))
      (println (format "PID:     %s" (or (:pid started) "N/A")))
      (println (format "Config:  %s" (or (:config-file started) "N/A")))
      (when stopped
        (println (format "Stopped: %s" (:stopped-at stopped))))
      (println (format "Cycles:  %d" (count cycles)))
      (println (format "PRs:     merged=%d failed=%d" (:merged metrics) (:failed metrics)))
      (println (format "Tasks:   claimed=%d completed=%d created=n/a"
                       (:claimed metrics) (:completed metrics)))
      (println (format "Reviews: %d" (count reviews)))
      (println)
      (println "Workers:")
      (println "ID  | Runtime   | Progress | Last Outcome   | Claimed | Model")
      (println "----+-----------+----------+----------------+---------+------------------------------")
      (doseq [w (sort-by :id workers)]
        (let [wid (:id w)
              latest (get latest-by-worker wid)
              progress (format "%d/%d" (or (:cycle latest) 0) (or (:iterations w) 0))
              runtime (worker-runtime w latest run-state*)
              outcome (or (:outcome latest) "-")
              claimed (count (or (:claimed-task-ids latest) []))]
          (println (format "%-3s | %-9s | %-8s | %-14s | %-7d | %s"
                           wid runtime progress outcome claimed (model-label w))))))
    (do
      (println (format "Swarm not found: %s" swarm-id))
      (System/exit 1))))

(defn cmd-list
  "List recent swarms with liveness + activity metrics.
   Default: 20 most recent. Use --all for full history."
  [opts args]
  (let [run-ids (or (runs/list-runs) [])]
    (if-not (seq run-ids)
      (println "No swarm runs found.")
      (let [shown (if (:all opts) run-ids (take 20 run-ids))]
        (println "Swarm Runs:")
        (println "ID       | State            | PID    | Workers | Active | Cycles | Merged | Failed | Done | Started")
        (println "---------+------------------+--------+---------+--------+--------+--------+--------+------+-------------------------")
        (doseq [rid shown]
          (let [started (runs/read-started rid)
                stopped (runs/read-stopped rid)
                cycles (or (runs/list-cycles rid) [])
                workers (or (:workers started) [])
                metrics (run-metrics cycles)
                latest-by-worker (latest-cycles-by-worker cycles)
                state* (run-state started stopped)
                active-count (if (= state* "running")
                               (count (filter (fn [w]
                                                (let [latest (get latest-by-worker (:id w))]
                                                  (< (or (:cycle latest) 0)
                                                     (or (:iterations w) 0))))
                                              workers))
                               0)]
            (println (format "%-8s | %-16s | %-6s | %7d | %6d | %6d | %6d | %6d | %4d | %s"
                             rid
                             state*
                             (or (:pid started) "-")
                             (count workers)
                             active-count
                             (count cycles)
                             (:merged metrics)
                             (:failed metrics)
                             (:completed metrics)
                             (or (:started-at started) "-")))))
        (when (and (not (:all opts)) (> (count run-ids) 20))
          (println (format "\nShowing 20 of %d runs. Use --all for full history." (count run-ids))))
        (println)
        (println "Use `oompa view <swarm-id>` for detailed single-swarm info.")))))

(defn cmd-view
  "Show detailed runtime for one swarm (default: latest run)."
  [opts args]
  (if-let [swarm-id (or (first args) (first (runs/list-runs)))]
    (cmd-view-one swarm-id)
    (println "No swarm runs found.")))

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
  (doseq [harness-kw (sort (harness/known-harnesses))]
    (let [available? (harness/check-available harness-kw)]
      (println (format "  %s: %s"
                       (name harness-kw)
                       (if available? "✓ available" "✗ not found"))))))

(def ^:private reasoning-variants
  #{"minimal" "low" "medium" "high" "max" "xhigh"})

(defn- parse-model-string
  "Parse model string into {:harness :model :reasoning}.

   Supported formats:
   - harness:model
   - harness:model:reasoning (codex only)
   - model (defaults harness to :codex)

   Note: non-codex model identifiers may contain ':' (for example
   openrouter/...:free). Those suffixes are preserved in :model."
  [s]
  (if (and s (str/includes? s ":"))
    (let [[harness-str rest*] (str/split s #":" 2)
          harness (keyword harness-str)]
      (if (contains? harnesses harness)
        (if (= harness :codex)
          ;; Codex may include a reasoning suffix at the end. Only treat the
          ;; last segment as reasoning if it matches a known variant.
          (if-let [idx (str/last-index-of rest* ":")]
            (let [model* (subs rest* 0 idx)
                  reasoning* (subs rest* (inc idx))]
              (if (contains? reasoning-variants reasoning*)
                {:harness harness :model model* :reasoning reasoning*}
                {:harness harness :model rest*}))
            {:harness harness :model rest*})
          ;; Non-codex: preserve full model string (including any ':suffix').
          {:harness harness :model rest*})
        ;; Not a known harness prefix, treat as raw model on default harness.
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
      (println "    {\"model\": \"claude:opus\", \"prompt\": [\"prompts/base.md\", \"prompts/planner.md\"], \"count\": 1},")
      (println "    {\"model\": \"gemini:gemini-3-pro-preview\", \"prompt\": [\"prompts/executor.md\"], \"count\": 1}")
      (println "  ]")
      (println "}")
      (println)
      (println "prompt: string or array of paths — concatenated into one prompt.")
      (System/exit 1))
    ;; Preflight: abort if git is dirty to prevent merge conflicts
    (check-git-clean!)
    ;; Preflight: abort if stale worktrees from prior runs would poison git
    (check-stale-worktrees!)

    (let [config (json/parse-string (slurp f) true)
          ;; Parse reviewer config — supports both formats:
          ;; Legacy: {"review_model": "harness:model:reasoning"}
          ;; New:    {"reviewer": {"model": "harness:model:reasoning", "prompt": ["path.md"]}}
          reviewer-config (:reviewer config)
          review-parsed (cond
                          reviewer-config
                          (let [parsed (parse-model-string (:model reviewer-config))
                                prompts (let [p (:prompt reviewer-config)]
                                          (cond (vector? p) p
                                                (string? p) [p]
                                                :else []))]
                            (assoc parsed :prompts prompts))

                          (:review_model config)
                          (parse-model-string (:review_model config))

                          :else nil)

          ;; Parse planner config — optional dedicated planner
          ;; Runs in project root, no worktree/review/merge, respects max_pending backpressure
          planner-config (:planner config)
          planner-parsed (when planner-config
                           (let [parsed (parse-model-string (:model planner-config))
                                 prompts (let [p (:prompt planner-config)]
                                           (cond (vector? p) p
                                                 (string? p) [p]
                                                 :else []))]
                             (assoc parsed
                                    :prompts prompts
                                    :max-pending (or (:max_pending planner-config) 10))))

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
                           :wait-between (:wait_between wc)
                           :max-wait-for-tasks (:max_wait_for_tasks wc)
                           :max-working-resumes (:max_working_resumes wc)
                           :review-harness (:harness review-parsed)
                           :review-model (:model review-parsed)
                           :review-prompts (:prompts review-parsed)})))
                    expanded-workers)]

      (println (format "Swarm config from %s:" config-file))
      (println (format "  Swarm ID: %s" swarm-id))
      (when planner-parsed
        (println (format "  Planner: %s:%s (max_pending: %d%s)"
                         (name (:harness planner-parsed))
                         (:model planner-parsed)
                         (:max-pending planner-parsed)
                         (if (seq (:prompts planner-parsed))
                           (str ", prompts: " (str/join ", " (:prompts planner-parsed)))
                           ""))))
      (when review-parsed
        (println (format "  Reviewer: %s:%s%s"
                         (name (:harness review-parsed))
                         (:model review-parsed)
                         (if (seq (:prompts review-parsed))
                           (str " (prompts: " (str/join ", " (:prompts review-parsed)) ")")
                           ""))))
      (println (format "  Workers: %d total" (count workers)))
      (doseq [[idx wc] (map-indexed vector worker-configs)]
        (let [{:keys [harness model reasoning]} (parse-model-string (:model wc))]
          (println (format "    - %dx %s:%s%s (%d iters%s)"
                           (or (:count wc) 1)
                           (name harness)
                           model
                           (if reasoning (str ":" reasoning) "")
                           (or (:iterations wc) 10)
                           (if (:prompt wc) (str ", " (:prompt wc)) "")))))
      (println)

      ;; Preflight: probe each unique model before launching workers
      ;; Include planner model in validation if configured
      (validate-models! (cond-> worker-configs
                          planner-config (conj planner-config))
                        review-parsed)

      ;; Write started event to runs/{swarm-id}/started.json
      (runs/write-started! swarm-id
                           {:workers workers
                            :planner-config planner-parsed
                            :reviewer-config review-parsed
                            :config-file config-file})
      (println (format "\nStarted event written to runs/%s/started.json" swarm-id))

      ;; Run planner if configured — synchronously before workers
      (when planner-parsed
        (println)
        (println (format "  Planner: %s:%s (max_pending: %d)"
                         (name (:harness planner-parsed))
                         (:model planner-parsed)
                         (:max-pending planner-parsed)))
        (worker/run-planner! (assoc planner-parsed :swarm-id swarm-id)))

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

(defn- find-latest-swarm-id
  "Find the most recent swarm ID from runs/ directory."
  []
  (first (runs/list-runs)))

(defn- read-swarm-pid
  "Read PID from started.json for a swarm. Returns nil if not found."
  [swarm-id]
  (when-let [started (runs/read-started swarm-id)]
    (:pid started)))

(defn- pid-alive?
  "Check if a process is alive via kill -0."
  [pid]
  (try
    (zero? (:exit (process/sh ["kill" "-0" (str pid)]
                              {:out :string :err :string})))
    (catch Exception _ false)))

(defn cmd-stop
  "Send SIGTERM to running swarm — workers finish current cycle then exit"
  [opts args]
  (let [swarm-id (or (first args) (find-latest-swarm-id))]
    (if-not swarm-id
      (println "No swarm runs found.")
      (let [stopped (runs/read-stopped swarm-id)]
        (if stopped
          (println (format "Swarm %s already stopped (reason: %s)" swarm-id (:reason stopped)))
          (let [pid (read-swarm-pid swarm-id)]
            (if-not pid
              (println (format "No PID found for swarm %s" swarm-id))
              (if-not (pid-alive? pid)
                (do
                  (println (format "Swarm %s PID %s is not running (stale). Writing stopped event." swarm-id pid))
                  (runs/write-stopped! swarm-id :interrupted))
                (do
                  (println (format "Sending SIGTERM to swarm %s (PID %s)..." swarm-id pid))
                  (println "Workers will finish their current cycle and exit.")
                  (process/sh ["kill" (str pid)]))))))))))

(defn cmd-kill
  "Send SIGKILL to running swarm — immediate termination"
  [opts args]
  (let [swarm-id (or (first args) (find-latest-swarm-id))]
    (if-not swarm-id
      (println "No swarm runs found.")
      (let [stopped (runs/read-stopped swarm-id)]
        (if stopped
          (println (format "Swarm %s already stopped (reason: %s)" swarm-id (:reason stopped)))
          (let [pid (read-swarm-pid swarm-id)]
            (if-not pid
              (println (format "No PID found for swarm %s" swarm-id))
              (if-not (pid-alive? pid)
                (do
                  (println (format "Swarm %s PID %s is not running (stale). Writing stopped event." swarm-id pid))
                  (runs/write-stopped! swarm-id :interrupted))
                (do
                  (println (format "Sending SIGKILL to swarm %s (PID %s)..." swarm-id pid))
                  ;; SIGKILL bypasses JVM shutdown hooks, so write stopped.json here
                  (process/sh ["kill" "-9" (str pid)])
                  (runs/write-stopped! swarm-id :interrupted)
                  (println "Swarm killed."))))))))))

(defn cmd-help
  "Print usage information"
  [opts args]
  (println "AgentNet Orchestrator")
  (println)
  (println "Usage: ./swarm.bb <command> [options]")
  (println)
  (println "Commands:")
  (println "  run [file]       Run swarm from config (default: oompa.json, oompa/oompa.json)")
  (println "  loop N           Run N iterations")
  (println "  swarm [file]     Run multiple worker configs from oompa.json (parallel)")
  (println "  tasks            Show task status (pending/current/complete)")
  (println "  prompt \"...\"     Run ad-hoc prompt")
  (println "  status           Show last run summary")
  (println "  list             List recent swarms (default: 20, --all for full history)")
  (println "  view [swarm-id]  Show detailed single-swarm runtime (default: latest)")
  (println "  worktrees        List worktree status")
  (println "  stop [swarm-id]  Stop swarm gracefully (finish current cycle)")
  (println "  kill [swarm-id]  Kill swarm immediately (SIGKILL)")
  (println "  cleanup          Remove all worktrees")
  (println "  context          Print context block")
  (println "  check            Check agent backends")
  (println "  help             Show this help")
  (println)
  (println "Options:")
  (println "  --workers N              Number of parallel workers (default: 2)")
  (println "  --workers H:N [H:N ...]  Mixed workers by harness (e.g., claude:5 opencode:2)")
  (println "  --all                    Show full history for list command")
  (println "  --config PATH            Config file for run/swarm")
  (println "  --detach                 Run in background (run command)")
  (println "  --startup-timeout N      Detached startup validation window in seconds")
  (println "  --iterations N           Number of iterations per worker (default: 1)")
  (println (str "  --harness {" (str/join "," (map name (sort harnesses))) "} Agent harness to use (default: codex)"))
  (println "  --model MODEL            Model to use (e.g., codex:gpt-5.3-codex:medium, claude:opus, gemini:gemini-3-pro-preview)")
  (println "  --dry-run                Skip actual merges")
  (println "  --keep-worktrees         Don't cleanup worktrees after run")
  (println)
  (println "Examples:")
  (println "  ./swarm.bb list")
  (println "  ./swarm.bb list --all")
  (println "  ./swarm.bb view 6cd50f5a")
  (println "  ./swarm.bb run --detach --config oompa/oompa_overnight_self_healing.json")
  (println "  ./swarm.bb loop 10 --harness codex --model gpt-5.3-codex --workers 3")
  (println "  ./swarm.bb loop --workers claude:5 opencode:2 --iterations 20")
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
   "list" cmd-list
   "view" cmd-view
   "stop" cmd-stop
   "kill" cmd-kill
   "worktrees" cmd-worktrees
   "cleanup" cmd-cleanup
   "context" cmd-context
   "check" cmd-check
   "help" cmd-help})

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (if-let [handler (get commands cmd)]
      (try
        (let [{:keys [opts args]} (parse-args rest-args)]
          (handler opts args))
        (catch Exception e
          (binding [*out* *err*]
            (println (format "Error: %s" (.getMessage e))))
          (System/exit 1)))
      (do
        (cmd-help {} [])
        (when cmd
          (println)
          (println (format "Unknown command: %s" cmd)))
        (System/exit (if cmd 1 0))))))
