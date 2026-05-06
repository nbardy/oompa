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
    (when-not (harness/valid-harness? h)
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
          (when-not (harness/valid-harness? h)
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
  "Warn if git working tree is dirty. Dirty index may cause merge conflicts."
  []
  (let [result (process/sh ["git" "status" "--porcelain"]
                           {:out :string :err :string})
        output (str/trim (:out result))]
    (when (and (zero? (:exit result)) (not (str/blank? output)))
      (println "WARNING: Git working tree is dirty. You may experience merge conflicts.")
      (println output))))

(defn- dirty-worktree?
  "Returns true if the git worktree at path has uncommitted changes."
  [path]
  (let [{:keys [exit out]} (process/sh ["git" "-C" path "status" "--porcelain"]
                                       {:out :string :err :string})]
    (and (zero? exit) (not (str/blank? out)))))

(defn- worktree-branch-name
  "Returns the current branch name for the worktree at path, or nil on failure."
  [path]
  (let [{:keys [exit out]} (process/sh ["git" "-C" path "rev-parse" "--abbrev-ref" "HEAD"]
                                       {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn- remove-stale-worktree!
  "Remove a stale worktree directory and delete its branch."
  [path branch]
  (process/sh ["git" "worktree" "remove" "--force" path] {:out :string :err :string})
  (when (and branch (not (str/blank? branch)) (not= branch "HEAD"))
    (process/sh ["git" "branch" "-D" branch] {:out :string :err :string})))

(defn- run-stale-review!
  "Invoke the reviewer model on partial worktree changes.
   Tries each reviewer in the fallback chain until one returns a verdict.
   Returns :merge to merge the branch into main, :discard to throw it away."
  [reviewer-configs worktree-path branch]
  (let [diff-out (:out (process/sh ["git" "-C" worktree-path "diff" "HEAD"]
                                   {:out :string :err :string}))
        diff     (if (> (count diff-out) 8000)
                   (str (subs diff-out 0 8000) "\n...[diff truncated at 8000 chars]")
                   diff-out)
        status-out (:out (process/sh ["git" "-C" worktree-path "status" "--short"]
                                     {:out :string}))
        prompt   (str "You are reviewing partial/incomplete changes from an interrupted swarm run.\n\n"
                      "Branch: " branch "\n"
                      "Status:\n" status-out "\n\n"
                      "Diff:\n```\n" diff "\n```\n\n"
                      "Should these changes be merged into main or discarded?\n"
                      "MERGE if: changes are correct, complete, or valuable enough to keep.\n"
                      "DISCARD if: changes are broken, trivial, or not worth merging.\n\n"
                      "Your verdict MUST appear on its own line, exactly one of:\n"
                      "VERDICT: MERGE\n"
                      "VERDICT: DISCARD\n\n"
                      "Then briefly explain why.\n")
        result   (reduce (fn [_ {:keys [harness model]}]
                           (try
                             (let [cmd (harness/build-cmd harness {:model model :prompt prompt})
                                   res (process/sh cmd
                                                   {:in  (harness/process-stdin harness prompt)
                                                    :out :string :err :string})
                                   output       (:out res)
                                   has-verdict? (or (re-find #"VERDICT:\s*MERGE" output)
                                                    (re-find #"VERDICT:\s*DISCARD" output))]
                               (if (and (zero? (:exit res)) has-verdict?)
                                 (reduced res)
                                 res))
                             (catch Exception e
                               {:exit -1 :out "" :err (.getMessage e)})))
                         {:exit -1 :out ""}
                         reviewer-configs)
        output   (:out result)]
    (cond
      (re-find #"VERDICT:\s*MERGE"   output) :merge
      (re-find #"VERDICT:\s*DISCARD" output) :discard
      :else                                   :discard)))

(defn- handle-stale-worktrees!
  "Non-destructive startup check for existing oompa worktrees.

   - Always runs `git worktree prune` to clear orphaned metadata.
   - Never auto-removes/merges/discards worktrees at startup.
   - Prints a warning summary so concurrent swarms are not disrupted.

   This avoids clobbering active work from another swarm in the same repo."
  [_reviewer-configs]
  ;; Step 1: prune orphaned git metadata first
  (let [prune-result (process/sh ["git" "worktree" "prune"] {:out :string :err :string})]
    (when-not (zero? (:exit prune-result))
      (println "WARNING: git worktree prune failed:")
      (println (:err prune-result))))

  ;; Step 2: discover existing oompa worktree dirs and oompa/* branches
  (let [ls-result          (process/sh ["find" "." "-maxdepth" "1" "-type" "d" "-name" ".w*-i*"]
                                       {:out :string})
        stale-dirs         (when (zero? (:exit ls-result))
                             (->> (str/split-lines (:out ls-result))
                                  (remove str/blank?)))
        br-result          (process/sh ["git" "branch" "--list" "oompa/*"] {:out :string})
        all-oompa-branches (when (zero? (:exit br-result))
                             (->> (str/split-lines (:out br-result))
                                  (map str/trim)
                                  (remove str/blank?)))]

    (when (or (seq stale-dirs) (seq all-oompa-branches))
      ;; Step 3: classify for warning output only (no mutation)
      (let [classified      (mapv (fn [dir]
                                    {:dir    dir
                                     :branch (worktree-branch-name dir)
                                     :dirty? (dirty-worktree? dir)})
                                  stale-dirs)
            clean           (filter (complement :dirty?) classified)
            dirty           (filter :dirty? classified)
            dir-branches    (set (keep :branch classified))
            orphan-branches (remove #(contains? dir-branches %) all-oompa-branches)]
        (println)
        (println "WARNING: Existing oompa worktrees/branches detected; leaving them untouched.")
        (println (format "  Worktrees: %d (%d dirty, %d clean)"
                         (count classified) (count dirty) (count clean)))
        (println (format "  Orphan branches: %d" (count orphan-branches)))
        (when (seq dirty)
          (println "  Dirty worktrees:")
          (doseq [{:keys [dir branch]} dirty]
            (println (format "    %s (branch: %s)" dir (or branch "unknown")))))
        (println "  Run `oompa cleanup` manually when you want to reclaim them.")))))

(defn- cleanup-iteration-worktrees!
  "Remove swarm iteration worktree dirs (.w*-i*) and oompa/* branches.
   Returns {:dirs-removed n :branches-removed n}."
  []
  (let [ls-result (process/sh ["find" "." "-maxdepth" "1" "-type" "d" "-name" ".w*-i*"]
                              {:out :string})
        dirs (if (zero? (:exit ls-result))
               (->> (str/split-lines (:out ls-result))
                    (remove str/blank?))
               [])
        _ (doseq [dir dirs]
            (remove-stale-worktree! dir (worktree-branch-name dir)))
        br-result (process/sh ["git" "branch" "--list" "oompa/*"] {:out :string})
        branches (if (zero? (:exit br-result))
                   (->> (str/split-lines (:out br-result))
                        (map str/trim)
                        (remove str/blank?))
                   [])
        ;; Branches may already be deleted by remove-stale-worktree!, so ignore failures.
        _ (doseq [b branches]
            (process/sh ["git" "branch" "-D" b] {:out :string :err :string}))]
    {:dirs-removed (count dirs)
     :branches-removed (count branches)}))


(defn- probe-model
  "Send 'say ok' to a model via its harness CLI. Returns true if model responds.
   Uses harness/build-probe-cmd for the command.
   For stdin-based harnesses (e.g. claude), delivers the probe prompt via stdin.
   For close-stdin harnesses, uses /dev/null to prevent hang."
  [harness-kw model]
  (try
    (let [cmd (harness/build-probe-cmd harness-kw model)
          probe-prompt "[_HIDE_TEST_] say ok"
          stdin-val (harness/process-stdin harness-kw probe-prompt)
          proc (process/process cmd {:out :string :err :string :in stdin-val})
          result (deref proc 60000 :timeout)]
      (if (= result :timeout)
        (do (.destroyForcibly (:proc proc)) false)
        (zero? (:exit result))))
    (catch Exception _ false)))

(defn- validate-models!
  "Probe each unique harness:model pair. Prints results and exits if any fail."
  [worker-configs review-models]
  (let [;; Deduplicate by harness:model only (ignore reasoning level)
        models (into (->> worker-configs
                            (map (fn [wc]
                                   (let [{:keys [harness model]} (parse-model-string (:model wc))]
                                     {:harness harness :model model})))
                            set)
                     (map #(select-keys % [:harness :model]) review-models))
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
  (cond-> ["nohup" "bb" "--classpath" (run-classpath) (run-script-path) "swarm"]
    (:dry-run opts) (conj "--dry-run")
    true (conj config-file)))

(defn- spawn-detached!
  [cmd log-file]
  (let [log (io/file log-file)
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.directory (io/file "."))
             (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log))
             (.redirectError (java.lang.ProcessBuilder$Redirect/appendTo log)))
        proc (.start pb)
        pid (.pid proc)]
    ;; Give spawn a short window before validation checks liveness.
    (Thread/sleep 100)
    pid))

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
    (println (format "ERROR: Config file not found: %s" (.getCanonicalPath (io/file config-file))))
    (println (format "       Working directory: %s" (.getCanonicalPath (io/file "."))))
    (println)
    (println "Tip: paths are relative to the working directory. Did you mean:")
    (println (format "       oompa run --config oompa/%s" (.getName (io/file config-file))))
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
                                 :max-cycles 1}))
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
                                 :max-cycles iterations}))
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
      (spit "config/tasks.json" (str (json/generate-string [task] {:pretty true}) "\n"))
      ;; Run
      (orchestrator/run-once! opts))))

(defn cmd-info
  "Show detailed information of a swarm run — reads event-sourced runs/{swarm-id}/ data."
  [opts args]
  (let [run-ids (runs/list-runs)]
    (if (seq run-ids)
      (let [target-ids (if (seq args) [(first args)] run-ids)]
        (doseq [swarm-id target-ids]
          (let [started (runs/read-started swarm-id)
                stopped (runs/read-stopped swarm-id)
                cycles (runs/list-cycles swarm-id)
                reviews (runs/list-reviews swarm-id)]
            (println "--------------------------------------------------")
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
                                 (:verdict r)))))
            (println))))
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
  #{"error" "merge-failed" "rejected" "stuck" "needs-followup"})

(def ^:private terminal-run-outcomes
  #{"merged" "rejected" "error" "merge-failed" "sync-failed" "stuck" "no-changes" "needs-followup"})

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
  [worker latest-cycle worker-cycles run-state*]
  (let [cycle-cap (or (:max-cycles worker) 0)
        cycles-done (count (filter #(terminal-run-outcomes (:outcome %)) worker-cycles))
        outcome (or (:outcome latest-cycle) "-")]
    (cond
      (>= cycles-done cycle-cap) "completed"
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

(defn cmd-status
  "Show all running swarms and last 10 historical runs with config info."
  [opts args]
  (letfn [(config-summary [started]
            (let [cfg (or (:config-file started) "?")
                  worker-models (->> (:workers started)
                                     (map model-label)
                                     frequencies
                                     (map (fn [[m n]] (if (> n 1) (format "%dx %s" n m) m)))
                                     (str/join ", "))
                  planner (when-let [p (:planner started)]
                            (str (:harness p) ":" (:model p)))]
              (str cfg " | workers: [" worker-models "]"
                   (when planner (str " | planner: " planner)))))
          (run-entry [run-id]
            (let [started (runs/read-started run-id)
                  stopped (runs/read-stopped run-id)
                  cycles (or (runs/list-cycles run-id) [])
                  state* (run-state started stopped)
                  metrics (run-metrics cycles)]
              {:id run-id
               :state state*
               :pid (or (:pid started) "-")
               :workers (count (or (:workers started) []))
               :cycles (count cycles)
               :merged (:merged metrics)
               :failed (:failed metrics)
               :started-at (or (:started-at started) "-")
               :config-summary (when started (config-summary started))}))
          (print-run [r]
            (println (format "  %s | %-16s | %d workers | %d cycles | %d merged | %d failed"
                             (:id r) (:state r) (:workers r) (:cycles r) (:merged r) (:failed r)))
            (when (:config-summary r)
              (println (format "    config: %s" (:config-summary r))))
            (println (format "    started: %s" (:started-at r))))]
    (let [run-ids (or (runs/list-runs) [])]
      (if-not (seq run-ids)
        (println "No swarms found.")
        (let [all-entries (mapv run-entry run-ids)
              running (filter #(= "running" (:state %)) all-entries)
              dead (remove #(= "running" (:state %)) all-entries)
              history (take 10 dead)]
          ;; Running swarms
          (println (format "=== Running Swarms (%d) ===" (count running)))
          (if (seq running)
            (doseq [r running] (print-run r))
            (println "  (none)"))
          (println)
          ;; History
          (println (format "=== Recent History (last %d) ===" (count history)))
          (if (seq history)
            (doseq [r history] (print-run r))
            (println "  (none)"))
          (when (> (count dead) 10)
            (println (format "\n  ... %d more. Use `oompa list --all` for full history." (- (count dead) 10)))))))))

(defn- cmd-view-one
  [swarm-id]
  (if-let [started (runs/read-started swarm-id)]
    (let [stopped (runs/read-stopped swarm-id)
          cycles (or (runs/list-cycles swarm-id) [])
          reviews (or (runs/list-reviews swarm-id) [])
          workers (or (:workers started) [])
          run-state* (run-state started stopped)
          metrics (run-metrics cycles)
          latest-by-worker (latest-cycles-by-worker cycles)
          cycles-by-worker (group-by :worker-id cycles)]
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
      (println "ID  | Runtime   | Cycles     | Last Outcome   | Claimed | Model")
      (println "----+-----------+------------+----------------+---------+------------------------------")
      (doseq [w (sort-by :id workers)]
        (let [wid (:id w)
              latest (get latest-by-worker wid)
              worker-cycles (or (get cycles-by-worker wid) [])
              cycle-cap (or (:max-cycles w) 0)
              cycles-done (count (filter #(terminal-run-outcomes (:outcome %)) worker-cycles))
              runtime (worker-runtime w latest worker-cycles run-state*)
              outcome (or (:outcome latest) "-")
              claimed (count (or (:claimed-task-ids latest) []))]
          (println (format "%-3s | %-9s | %4d/%-5d | %-14s | %-7d | %s"
                           wid runtime cycles-done cycle-cap outcome claimed (model-label w))))))
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
                cycles-by-worker (group-by :worker-id cycles)
                state* (run-state started stopped)
                active-count (if (= state* "running")
                               (count (filter (fn [w]
                                                (let [wid (:id w)
                                                      cycle-cap (or (:max-cycles w) 0)
                                                      cycles-done (count (filter #(terminal-run-outcomes (:outcome %))
                                                                                 (or (get cycles-by-worker wid) [])))]
                                                  (< cycles-done cycle-cap)))
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
  (let [state-file (io/file ".workers/state.json")]
    (if (.exists state-file)
      (let [pool (json/parse-string (slurp state-file) true)
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
  "Remove all worktrees (legacy pool + swarm iteration worktrees)."
  [opts args]
  (let [state-file (io/file ".workers/state.json")]
    (println "Removing worktrees...")
    (if (.exists state-file)
      (let [pool (json/parse-string (slurp state-file) true)]
        (worktree/cleanup-pool! pool))
      (println "No legacy pool worktrees to clean up."))
    (let [{:keys [dirs-removed branches-removed]} (cleanup-iteration-worktrees!)]
      (println (format "Removed %d swarm worktree dir(s) and %d oompa branch(es)."
                       dirs-removed branches-removed)))
    (println "Done.")))

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
   - harness:model:reasoning (if reasoning is in reasoning-variants)
   - model (defaults harness to :codex)

   Note: model identifiers may contain ':' (for example openrouter/...:free).
   Those suffixes are preserved in :model if not a known reasoning variant."
  [s]
  (if (and s (str/includes? s ":"))
    (let [[harness-str rest*] (str/split s #":" 2)
          harness (keyword harness-str)]
      (if (harness/valid-harness? harness)
        ;; Check for reasoning suffix for any valid harness
        (if-let [idx (str/last-index-of rest* ":")]
          (let [model* (subs rest* 0 idx)
                reasoning* (subs rest* (inc idx))]
            (if (contains? reasoning-variants reasoning*)
              {:harness harness :model model* :reasoning reasoning*}
              {:harness harness :model rest*}))
          {:harness harness :model rest*})
        ;; Not a known harness prefix — check for trailing reasoning variant
        ;; so bare "gpt-5.4:xhigh" parses as {:harness :codex :model "gpt-5.4" :reasoning "xhigh"}
        (if-let [idx (str/last-index-of s ":")]
          (let [model* (subs s 0 idx)
                reasoning* (subs s (inc idx))]
            (if (contains? reasoning-variants reasoning*)
              {:harness :codex :model model* :reasoning reasoning*}
              {:harness :codex :model s}))
          {:harness :codex :model s})))
    {:harness :codex :model s}))

(defn- parse-reviewer-entry
  "Parse reviewer config entry from either:
   1) string model spec: \"harness:model[:reasoning]\"
   2) map: {:model \"...\" :prompt \"path\"|[...]}.
   Returns nil for invalid entries."
  [entry]
  (cond
    (string? entry)
    (parse-model-string entry)

    (map? entry)
    (let [model (:model entry)]
      (when (string? model)
        (let [parsed (parse-model-string model)
              prompts (let [p (:prompt entry)]
                        (cond
                          (vector? p) p
                          (string? p) [p]
                          :else []))]
          (assoc parsed :prompts prompts))))

    :else
    nil))

(defn- review-enabled?
  "Review is enabled unless config explicitly sets needs_review to false."
  [config]
  (not= false (:needs_review config)))

(defn- parse-reviewers-config
  "Parse reviewer config from either a top-level or worker config map.
   Returns [] when no reviewer is configured or review is disabled for that scope."
  [config]
  (if-not (review-enabled? config)
    []
    (cond
      (:reviewers config)
      (->> (:reviewers config)
           (map parse-reviewer-entry)
           (remove nil?)
           vec)

      (:review_models config)
      (->> (:review_models config)
           (map parse-reviewer-entry)
           (remove nil?)
           vec)

      (:reviewer config)
      (->> [(:reviewer config)]
           (map parse-reviewer-entry)
           (remove nil?)
           vec)

      (:review_model config)
      (->> [(:review_model config)]
           (map parse-reviewer-entry)
           (remove nil?)
           vec)

      :else [])))

(defn- resolve-worker-reviewers
  "Resolve final reviewer list for a worker.
   Worker-level needs_review=false suppresses all review, including inherited
   top-level reviewers."
  [worker-config generic-reviewers]
  (if-not (review-enabled? worker-config)
    []
    (->> (concat (parse-reviewers-config worker-config) generic-reviewers)
         (map #(select-keys % [:harness :model :reasoning :prompts]))
         distinct
         vec)))

(defn cmd-swarm
  "Run multiple worker configs from oompa.json in parallel"
  [opts args]
  (let [config-file (or (first args) "oompa.json")
        f (io/file config-file)
        swarm-id (make-swarm-id)]
    (when-not (.exists f)
      (println (format "ERROR: Config file not found: %s" (.getCanonicalPath f)))
      (println (format "       Working directory: %s" (.getCanonicalPath (io/file "."))))
      (println)
      (println "Tip: paths are relative to the working directory. Did you mean:")
      (println (format "       oompa run --config oompa/%s" (.getName f)))
      (System/exit 1))
    ;; Preflight: abort if git is dirty to prevent merge conflicts
    (check-git-clean!)

    (let [config (json/parse-string (slurp f) true)
          ;; Parse reviewer config — supports legacy + new formats.
          generic-reviewers (parse-reviewers-config config)

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

          ;; Require max_cycle to be present on all workers
          _ (doseq [[idx wc] (map-indexed vector worker-configs)]
              (when (or (:iterations wc) (:max_cycles wc))
                (println (format "ERROR: Worker %d uses deprecated 'iterations' or 'max_cycles'. Consolidate strictly on 'max_cycle'." idx))
                (System/exit 1))
              (when-not (:max_cycle wc)
                (println (format "ERROR: Worker %d missing 'max_cycle' in config." idx))
                (System/exit 1)))


          ;; Expand worker configs by count
          expanded-workers (mapcat (fn [wc]
                                     (let [cnt (or (:count wc) 1)]
                                       (repeat cnt (dissoc wc :count))))
                                   worker-configs)

          ;; Convert to worker format
          workers (map-indexed
                    (fn [idx wc]
                      (let [{:keys [harness model reasoning]} (parse-model-string (:model wc))
                            all-reviewers (resolve-worker-reviewers wc generic-reviewers)]
                        (worker/create-worker
                          {:id (str "w" idx)
                           :swarm-id swarm-id
                           :harness harness
                           :model model
                           :reasoning reasoning
                           :role (or (:_role wc) (:role wc))
                           :can-claim-gpu (:can_claim_gpu wc)
                           :max-cycles (:max_cycle wc)
                           :prompts (:prompt wc)
                           :can-plan (:can_plan wc)
                           :wait-between (:wait_between wc)
                           :max-wait-for-tasks (:max_wait_for_tasks wc)
                           :max-working-resumes (:max_working_resumes wc)
                           :max-resumes (:max_resumes wc)
                           :reviewers all-reviewers})))
                    expanded-workers)]

      ;; Preflight: handle stale worktrees from prior runs before launching workers.
      ;; Empty ones are auto-cleaned silently; dirty ones trigger an interactive review.
      (handle-stale-worktrees! generic-reviewers)

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
      (when (seq generic-reviewers)
        (println (format "  Generic Reviewers: %s"
                         (str/join ", " (map #(str (name (:harness %)) ":" (:model %)) generic-reviewers)))))
      (println (format "  Workers: %d total" (count workers)))
      (doseq [[idx wc] (map-indexed vector worker-configs)]
        (let [{:keys [harness model reasoning]} (parse-model-string (:model wc))]
          (println (format "    - %dx %s:%s%s (max_cycle=%d, max_resumes=%d%s)"
                           (or (:count wc) 1)
                           (name harness)
                           model
                           (if reasoning (str ":" reasoning) "")
                           (:max_cycle wc)
                           (or (:max_resumes wc) 7)
                           (if (:prompt wc) (str ", " (:prompt wc)) "")))))
      (println)

      ;; Preflight: probe each unique model before launching workers
      ;; Include planner model in validation if configured
      (validate-models! (cond-> worker-configs
                          planner-config (conj planner-config))
                        generic-reviewers)

      ;; Write started event to runs/{swarm-id}/started.json
      (runs/write-started! swarm-id
                           {:workers workers
                            :planner-config planner-parsed
                            :reviewer-configs generic-reviewers
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

(defn cmd-requeue
  "Move current/ tasks back to pending/.
   With args, only requeue those task IDs. Without args, requeue all current tasks."
  [opts args]
  (tasks/ensure-dirs!)
  (let [current-ids (->> (tasks/list-current) (map :id) set)
        requested-ids (if (seq args) (set args) current-ids)
        recyclable-ids (set (filter current-ids requested-ids))
        recycled (if (seq args)
                   (tasks/recycle-tasks! recyclable-ids)
                   (tasks/recycle-all-current!))
        missing (sort (remove recyclable-ids requested-ids))]
    (if (seq recycled)
      (println (format "Requeued %d task(s): %s"
                       (count recycled)
                       (str/join ", " recycled)))
      (println "No current tasks were requeued."))
    (when (seq missing)
      (println (format "Not in current/: %s" (str/join ", " missing))))))

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
  (println "  requeue [ids..]  Move current tasks back to pending")
  (println "  prompt \"...\"     Run ad-hoc prompt")
  (println "  status           Show running swarms")
  (println "  info             Show detailed summary of the last run")
  (println "  list             List recent swarms (default: 20, --all for full history)")
  (println "  view [swarm-id]  Show detailed single-swarm runtime (default: latest)")
  (println "  worktrees        List worktree status")
  (println "  stop [swarm-id]  Stop swarm gracefully (finish current cycle)")
  (println "  kill [swarm-id]  Kill swarm immediately (SIGKILL)")
  (println "  cleanup          Remove all worktrees")
  (println "  salvage [id]     Resume dead workers from a swarm (tries session resume, falls back to fresh)")
  (println "  context          Print context block")
  (println "  check            Check agent backends")
  (println "  help             Show this help")
  (println "  docs             Dump all core architecture and swarm design docs")
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

(defn cmd-docs
  "Dump core architecture and design documents"
  [opts args]
  (let [docs-dir "docs"
        core-docs ["SWARM_PHILOSOPHY.md" "SWARM_GUIDE.md" "JSON_TICKETS.md" "SYSTEMS_DESIGN.md" "OOMPA.md"]
        package-dir (or (System/getenv "OOMPA_PACKAGE_ROOT") ".")
        doc-paths (map #(str package-dir "/" docs-dir "/" %) core-docs)]
    (println "# Oompa Loompas Core Documentation")
    (println)
    (doseq [path doc-paths]
      (try
        (let [content (slurp path)]
          (println (str "## " path))
          (println "```markdown")
          (println content)
          (println "```")
          (println))
        (catch Exception e
          (println (str "Could not read " path ": " (.getMessage e))))))))

;; =============================================================================
;; Salvage — resume dead/errored workers from a previous swarm
;; =============================================================================

(defn- parse-worktree-name
  "Parse .w{swarm-token}-{worker-id}-i{iteration} into components.
   Returns {:swarm-token :worker-id :iteration} or nil."
  [dir-name]
  (when-let [[_ swarm-token worker-id iteration]
             (re-matches #"\.?\.?ws?([a-f0-9]+)-(w\d+)-i(\d+)" dir-name)]
    {:swarm-token swarm-token
     :worker-id worker-id
     :iteration (Integer/parseInt iteration)}))

(defn- find-salvageable-branches
  "Find oompa/* branches that have commits ahead of main.
   Returns seq of {:branch :worker-id :swarm-token :ahead-count}."
  []
  (let [br-result (process/sh ["git" "branch" "--list" "oompa/*"] {:out :string})
        branches (when (zero? (:exit br-result))
                   (->> (str/split-lines (:out br-result))
                        (map str/trim)
                        (remove str/blank?)
                        (remove #(= % "*"))))]
    (->> branches
         (keep (fn [branch]
                 (let [ahead (process/sh ["git" "rev-list" "--count" (str "main.." branch)]
                                         {:out :string :err :string})
                       count (try (Integer/parseInt (str/trim (:out ahead)))
                                  (catch Exception _ 0))
                       ;; Parse branch name: oompa/s{swarm-token}-{worker-id}-i{iteration}
                       parts (when-let [[_ swarm-token worker-id iteration]
                                        (re-matches #"oompa/s([a-f0-9]+)-(w\d+)-i(\d+)" branch)]
                               {:swarm-token swarm-token
                                :worker-id worker-id
                                :iteration (Integer/parseInt iteration)})]
                   (when (and parts (pos? count))
                     (assoc parts :branch branch :ahead-count count)))))
         vec)))

(defn- find-salvageable-worktrees
  "Find surviving worktree directories with changes.
   Returns seq of {:dir :branch :worker-id :swarm-token :has-changes?}."
  []
  (let [ls-result (process/sh ["find" "." "-maxdepth" "1" "-type" "d" "-name" ".w*-i*"]
                              {:out :string})
        dirs (when (zero? (:exit ls-result))
               (->> (str/split-lines (:out ls-result))
                    (remove str/blank?)))]
    (->> dirs
         (keep (fn [dir]
                 (let [parts (parse-worktree-name dir)
                       branch (worktree-branch-name dir)
                       has-changes? (or (dirty-worktree? dir)
                                        (let [ahead (process/sh ["git" "-C" dir "rev-list" "--count" "main..HEAD"]
                                                                {:out :string :err :string})
                                              cnt (try (Integer/parseInt (str/trim (:out ahead)))
                                                       (catch Exception _ 0))]
                                          (pos? cnt)))]
                   (when parts
                     (assoc parts :dir dir :branch branch :has-changes? has-changes?)))))
         vec)))

(defn- last-cycle-for-worker
  "Find the last cycle log for a worker in a swarm. Returns parsed JSON or nil."
  [swarm-id worker-id]
  (let [cycles (runs/list-cycles swarm-id)]
    (->> cycles
         (filter #(= (:worker-id %) worker-id))
         (sort-by :cycle)
         last)))

(defn- reviews-for-worker-cycle
  "Get all review logs for a specific worker cycle, sorted by round."
  [swarm-id worker-id cycle]
  (let [reviews (runs/list-reviews swarm-id)]
    (->> reviews
         (filter #(and (= (:worker-id %) worker-id)
                       (= (:cycle %) cycle)))
         (sort-by :round)
         vec)))

(defn- build-salvage-prompt
  "Build a prompt for a fresh session that inherits partial work context.
   Includes: the diff, review history, and instructions to finish + merge."
  [wt-path branch review-history worker-prompts]
  (let [diff-result (process/sh ["git" "diff" "-U5" "main"]
                                {:dir wt-path :out :string :err :string})
        diff (let [d (:out diff-result)]
               (if (> (count d) 24000)
                 (str (subs d 0 24000) "\n...[diff truncated at 24000 chars]")
                 d))
        log-result (process/sh ["git" "log" "--oneline" "main..HEAD"]
                               {:dir wt-path :out :string :err :string})
        commit-log (:out log-result)

        ;; Load worker prompt files for role context
        prompt-context (when (seq worker-prompts)
                         (->> worker-prompts
                              (keep (fn [p]
                                      (try (slurp p)
                                           (catch Exception _ nil))))
                              (str/join "\n\n")))

        ;; Format review history
        review-text (when (seq review-history)
                      (str "## Previous Review Feedback\n\n"
                           (->> review-history
                                (map (fn [{:keys [round verdict output]}]
                                       (str "### Round " round " — " (or verdict "unknown") "\n"
                                            (or output "(no output)") "\n")))
                                (str/join "\n"))))]
    (str "# Salvage: Resume Interrupted Work\n\n"
         "You are picking up work from a previous agent session that was interrupted.\n"
         "The work is partially done — there are already commits on this branch.\n\n"
         (when prompt-context
           (str "## Your Role\n\n" prompt-context "\n\n"))
         "## Branch: " branch "\n\n"
         "## Commits on branch:\n```\n" (or commit-log "(none)") "\n```\n\n"
         "## Current diff vs main:\n```\n" diff "\n```\n\n"
         (or review-text "")
         "\n\n## Instructions\n\n"
         "1. Read the existing changes carefully.\n"
         "2. If there are review issues noted above, fix them.\n"
         "3. If the work looks incomplete, finish it.\n"
         "4. Run tests: `npm test` (fast, 3-4s). Do NOT run `npx vitest run`.\n"
         "5. When ready, signal COMPLETE_AND_READY_FOR_MERGE.\n")))

(defn- try-session-resume
  "Attempt to resume a Claude session. Returns {:ok? bool :session-id string}."
  [harness-kw session-id wt-path model]
  (try
    (let [probe-prompt (str "[salvage] Confirm you can see this session. "
                            "Reply with 'SESSION_ALIVE' if you have context from prior work.")
          abs-wt (.getAbsolutePath (io/file wt-path))
          result (harness/run-command! harness-kw
                                       {:cwd abs-wt
                                        :model model
                                        :session-id session-id
                                        :resume? true
                                        :prompt probe-prompt})
          {:keys [output]} (harness/parse-output harness-kw (:out result) session-id)]
      (if (and (zero? (:exit result))
               (not (str/blank? output))
               ;; Session is alive if it didn't error out
               (not (re-find #"(?i)error|not found|invalid session" (or output ""))))
        {:ok? true :session-id session-id}
        {:ok? false :reason "Session responded but may lack context"}))
    (catch Exception e
      {:ok? false :reason (.getMessage e)})))

(defn- salvage-worker!
  "Salvage a single dead worker: try resume, fall back to fresh session,
   then run review + merge. Returns {:outcome :details}."
  [salvage-info swarm-config]
  (let [{:keys [branch worker-id swarm-token]} salvage-info
        swarm-id (str swarm-token)
        ;; Try worktree dir first, create one from branch if needed
        wt-path (or (:dir salvage-info)
                    (let [wt-dir (format ".wsalvage-%s-%s" swarm-token worker-id)
                          _ (process/sh ["git" "worktree" "remove" wt-dir "--force"])
                          result (process/sh ["git" "worktree" "add" wt-dir branch]
                                             {:out :string :err :string})]
                      (when (zero? (:exit result))
                        wt-dir)))
        _ (when-not wt-path
            (println (format "[salvage:%s] Cannot create worktree for %s" worker-id branch))
            (throw (ex-info "Cannot create worktree" {:branch branch})))

        ;; Load cycle + review context
        last-cycle (last-cycle-for-worker swarm-id worker-id)
        cycle-num (or (:cycle last-cycle) 1)
        session-id (:session-id last-cycle)
        review-history (reviews-for-worker-cycle swarm-id worker-id cycle-num)
        {:keys [harness model prompts reviewers]} swarm-config

        ;; Step 1: Try session resume
        resumed? (when session-id
                   (println (format "[salvage:%s] Trying session resume %s..."
                                    worker-id (subs session-id 0 (min 8 (count session-id)))))
                   (let [{:keys [ok? reason]} (try-session-resume harness session-id wt-path model)]
                     (if ok?
                       (println (format "[salvage:%s] Session alive!" worker-id))
                       (println (format "[salvage:%s] Session gone: %s" worker-id (or reason "unknown"))))
                     ok?))

        ;; Step 2: If resume failed, start fresh session with context
        active-session-id
        (if resumed?
          ;; Resume the existing session — ask it to finish and signal merge
          (let [prompt (str "[salvage:" worker-id "] Your previous session was interrupted. "
                            "Please review your current changes, fix any outstanding issues, "
                            "run `npm test`, and signal COMPLETE_AND_READY_FOR_MERGE when ready.")
                abs-wt (.getAbsolutePath (io/file wt-path))
                result (harness/run-command! harness
                                             {:cwd abs-wt :model model
                                              :session-id session-id :resume? true
                                              :prompt prompt})
                {:keys [session-id]} (harness/parse-output harness (:out result) session-id)]
            session-id)

          ;; Fresh session with full context
          (let [prompt (build-salvage-prompt wt-path branch review-history prompts)
                abs-wt (.getAbsolutePath (io/file wt-path))
                fresh-session-id (harness/make-session-id harness)
                result (harness/run-command! harness
                                             {:cwd abs-wt :model model
                                              :session-id fresh-session-id :resume? false
                                              :prompt prompt})
                {:keys [session-id]} (harness/parse-output harness (:out result) fresh-session-id)]
            session-id))

        ;; Step 3: Review loop
        worker-config (worker/create-worker
                        {:id worker-id
                         :swarm-id swarm-id
                         :harness harness
                         :model model
                         :prompts prompts
                         :reviewers reviewers})
        review-result (worker/review-loop! worker-config wt-path worker-id 1
                                           [{:session-id active-session-id}])]

    (if (:approved? review-result)
      ;; Step 4: Merge
      (let [project-root (.getCanonicalPath (io/file "."))
            merge-result (worker/run-merge-agent! worker-config wt-path branch
                                                   project-root active-session-id worker-id)]
        (if (:ok? merge-result)
          (do
            (println (format "[salvage:%s] Merged → %s" worker-id (:sha merge-result)))
            ;; Complete claimed tasks
            (when-let [task-ids (seq (:claimed-task-ids last-cycle))]
              (let [completed (tasks/complete-by-ids! (set task-ids))]
                (println (format "[salvage:%s] Completed %d task(s)" worker-id (count completed)))))
            {:outcome :merged :sha (:sha merge-result)})
          (do
            (println (format "[salvage:%s] Merge failed: %s" worker-id (:message merge-result)))
            {:outcome :merge-failed :details (:message merge-result)})))
      (do
        (println (format "[salvage:%s] Review rejected after %d rounds"
                         worker-id (:attempts review-result)))
        {:outcome :rejected :attempts (:attempts review-result)}))))

(defn cmd-salvage
  "Salvage dead/errored workers from a previous swarm.
   Discovers surviving worktrees and branches with unmerged changes,
   tries to resume their Claude sessions, falls back to fresh sessions
   with the partial diff + review history as context."
  [opts args]
  (let [;; Find target swarm — from args or latest
        target-swarm-id (or (first args) (find-latest-swarm-id))
        _ (when-not target-swarm-id
            (println "No swarm runs found. Nothing to salvage.")
            (System/exit 0))

        ;; Read swarm config to reconstruct worker settings
        started (runs/read-started target-swarm-id)
        _ (when-not started
            (println (format "No started.json found for swarm %s" target-swarm-id))
            (System/exit 1))

        ;; Extract harness/model/prompts from first worker config
        ;; (salvage workers all use the same config)
        first-worker-config (first (:workers started))
        harness-kw (keyword (:harness first-worker-config))
        model (:model first-worker-config)
        prompts (or (:prompts first-worker-config) [])

        ;; Build reviewer configs from started.json
        reviewer-config (:reviewer started)
        reviewers (if reviewer-config
                    [(-> (parse-model-string (str (name (keyword (:harness reviewer-config)))
                                                  ":" (:model reviewer-config)))
                         (assoc :prompts (or (:prompts reviewer-config) [])))]
                    [])

        swarm-config {:harness harness-kw :model model :prompts prompts :reviewers reviewers}

        ;; Discover salvageable work
        worktrees (find-salvageable-worktrees)
        branches (find-salvageable-branches)
        ;; Filter to target swarm
        swarm-token (subs target-swarm-id 0 (min 8 (count target-swarm-id)))
        wt-candidates (->> worktrees
                           (filter #(= (:swarm-token %) swarm-token))
                           (filter :has-changes?))
        br-candidates (->> branches
                           (filter #(= (:swarm-token %) swarm-token))
                           ;; Exclude branches that already have a worktree
                           (remove (fn [b] (some #(= (:worker-id %) (:worker-id b)) wt-candidates))))]

    (println (format "Salvage scan for swarm %s:" target-swarm-id))
    (println (format "  Config: %s:%s" (name harness-kw) model))
    (println (format "  Worktrees with changes: %d" (count wt-candidates)))
    (println (format "  Orphan branches with commits: %d" (count br-candidates)))
    (println)

    (let [all-candidates (concat wt-candidates br-candidates)]
      (if (empty? all-candidates)
        (do
          (println "Nothing to salvage — all work was already merged or branches are at main.")
          (println)
          ;; Show cycle summary for context
          (let [cycles (runs/list-cycles target-swarm-id)
                by-outcome (group-by :outcome cycles)]
            (println "Cycle summary:")
            (doseq [[outcome cs] (sort-by key by-outcome)]
              (println (format "  %s: %d" outcome (count cs))))))

        (do
          (println "Salvageable workers:")
          (doseq [c all-candidates]
            (let [last-cycle (last-cycle-for-worker swarm-token (:worker-id c))
                  reviews (when last-cycle
                            (reviews-for-worker-cycle swarm-token (:worker-id c) (:cycle last-cycle)))]
              (println (format "  %s: branch=%s ahead=%s session=%s reviews=%d last-outcome=%s"
                               (:worker-id c)
                               (or (:branch c) "?")
                               (or (:ahead-count c) "?")
                               (if-let [sid (:session-id last-cycle)]
                                 (subs sid 0 (min 8 (count sid)))
                                 "none")
                               (count reviews)
                               (or (:outcome last-cycle) "unknown")))))
          (println)
          (println (format "Launching salvage for %d worker(s)..." (count all-candidates)))
          (println)

          ;; Run salvage workers in parallel
          (let [futures (doall
                          (map (fn [candidate]
                                 (future
                                   (try
                                     (salvage-worker! candidate swarm-config)
                                     (catch Exception e
                                       (println (format "[salvage:%s] ERROR: %s"
                                                        (:worker-id candidate) (.getMessage e)))
                                       {:outcome :error :details (.getMessage e)}))))
                               all-candidates))
                results (mapv deref futures)
                merged (count (filter #(= (:outcome %) :merged) results))
                failed (count (filter #(not= (:outcome %) :merged) results))]
            (println)
            (println (format "Salvage complete: %d merged, %d failed/rejected" merged failed))
            results))))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(def commands
  {"run" cmd-run
   "loop" cmd-loop
   "swarm" cmd-swarm
   "tasks" cmd-tasks
   "requeue" cmd-requeue
   "prompt" cmd-prompt
   "status" cmd-status
   "info" cmd-info
   "list" cmd-list
   "view" cmd-view
   "stop" cmd-stop
   "kill" cmd-kill
   "worktrees" cmd-worktrees
   "cleanup" cmd-cleanup
   "salvage" cmd-salvage
   "context" cmd-context
   "check" cmd-check
   "help" cmd-help
   "docs" cmd-docs})

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
