(ns agentnet.worker
  "Self-directed worker execution.

   Workers:
   1. Claim tasks from tasks/pending/ (mv → current/)
   2. Execute task in worktree
   3. Commit changes
   4. Reviewer checks work (if configured)
   5. If approved → merge to main, complete task
   6. If rejected → fix & retry → back to reviewer
   7. Can create new tasks in pending/
   8. Exit on __DONE__ signal

   No separate orchestrator - workers self-organize."
  (:require [agentnet.tasks :as tasks]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [agentnet.runs :as runs]
            [cheshire.core :as json]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Worker State
;; =============================================================================

(def ^:private package-root
  "Root of the oompa package — set by bin/oompa.js, falls back to cwd."
  (or (System/getenv "OOMPA_PACKAGE_ROOT") "."))

;; Serializes merge-to-main! calls across concurrent workers to prevent
;; git index corruption from parallel checkout+merge operations.
(def ^:private merge-lock (Object.))

;; Resolve absolute paths for CLI binaries at first use.
;; ProcessBuilder with :dir set can fail to find bare command names on some
;; platforms (macOS + babashka), so we resolve once via `which` and cache.
(def ^:private binary-paths* (atom {}))

(defn- resolve-binary!
  "Resolve the absolute path of a CLI binary. Caches result.
   Throws if binary not found on PATH."
  [name]
  (or (get @binary-paths* name)
      (let [result (try
                     (process/sh ["which" name] {:out :string :err :string})
                     (catch Exception _ {:exit -1 :out "" :err ""}))
            path (when (zero? (:exit result))
                   (str/trim (:out result)))]
        (if path
          (do (swap! binary-paths* assoc name path)
              path)
          (throw (ex-info (str "Binary not found on PATH: " name) {:binary name}))))))

(defn- load-prompt
  "Load a prompt file. Tries path as-is first, then from package root."
  [path]
  (or (agent/load-custom-prompt path)
      (agent/load-custom-prompt (str package-root "/" path))))

(defn- task-root-for-cwd
  "Return the relative tasks root for commands issued from cwd."
  [cwd]
  (let [cwd-file (io/file cwd)
        local-tasks (io/file cwd-file "tasks")
        parent-tasks (some-> cwd-file .getParentFile (io/file "tasks"))]
    (cond
      (.exists local-tasks) "tasks"
      (and parent-tasks (.exists parent-tasks)) "../tasks"
      :else "tasks")))

(defn- render-task-header
  "Inject runtime task path into auto-injected task header."
  [raw-header cwd]
  (str/replace (or raw-header "") "{{TASKS_ROOT}}" (task-root-for-cwd cwd)))

(defn create-worker
  "Create a worker config.
   :prompts is a string or vector of strings — paths to prompt files.
   :can-plan when false, worker waits for tasks before starting (backpressure).
   :reasoning reasoning effort level (e.g. \"low\", \"medium\", \"high\") — codex only.
   :review-prompts paths to reviewer prompt files (loaded and concatenated for review)."
  [{:keys [id swarm-id harness model iterations prompts can-plan reasoning
           review-harness review-model review-prompts]}]
  {:id id
   :swarm-id swarm-id
   :harness (or harness :codex)
   :model model
   :iterations (or iterations 10)
   :prompts (cond
              (vector? prompts) prompts
              (string? prompts) [prompts]
              :else [])
   :can-plan (if (some? can-plan) can-plan true)
   :reasoning reasoning
   :review-harness review-harness
   :review-model review-model
   :review-prompts (cond
                     (vector? review-prompts) review-prompts
                     (string? review-prompts) [review-prompts]
                     :else [])
   :completed 0
   :status :idle})

;; =============================================================================
;; Task Execution
;; =============================================================================

(def ^:private max-review-retries 3)

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

(defn- opencode-attach-url
  "Optional opencode server URL for run --attach mode."
  []
  (let [url (or (System/getenv "OOMPA_OPENCODE_ATTACH")
                (System/getenv "OPENCODE_ATTACH"))]
    (when (and url (not (str/blank? url)))
      url)))

(defn- parse-opencode-run-output
  "Parse `opencode run --format json` output.
   Returns {:session-id string|nil, :text string|nil}."
  [s]
  (let [raw (or s "")
        events (->> (str/split-lines raw)
                    (keep (fn [line]
                            (try
                              (json/parse-string line true)
                              (catch Exception _
                                nil))))
                    doall)
        session-id (or (some #(or (:sessionID %)
                                   (:sessionId %)
                                   (get-in % [:part :sessionID])
                                   (get-in % [:part :sessionId]))
                             events)
                       (some-> (re-find #"(ses_[A-Za-z0-9]+)" raw) second))
        text (->> events
                  (keep (fn [event]
                          (let [event-type (or (:type event) (get-in event [:part :type]))
                                chunk (or (:text event) (get-in event [:part :text]))]
                            (when (and (= event-type "text")
                                       (string? chunk)
                                       (not (str/blank? chunk)))
                              chunk))))
                  (str/join ""))]
    {:session-id session-id
     :text (when-not (str/blank? text) text)}))

(defn- run-agent!
  "Run agent with prompt, return {:output string, :done? bool, :merge? bool, :exit int, :session-id string}.
   When resume? is true and harness is :claude/:opencode, continues the existing session
   with a lighter prompt (just task status + continue instruction)."
  [{:keys [id swarm-id harness model prompts reasoning]} worktree-path context session-id resume?]
  (let [;; Use provided session-id, otherwise generate one for harnesses that accept custom IDs.
        session-id (or session-id
                       (when (#{:codex :claude} harness)
                         (str/lower-case (str (java.util.UUID/randomUUID)))))

        ;; Build prompt — lighter for resume (agent already has full context)
        prompt (if resume?
                 (str "Task Status: " (:task_status context) "\n"
                      "Pending: " (:pending_tasks context) "\n\n"
                      "Continue working. Signal COMPLETE_AND_READY_FOR_MERGE when your current task is done and ready for review.")
                 (let [task-header (render-task-header
                                     (load-prompt "config/prompts/_task_header.md")
                                     worktree-path)
                       user-prompts (if (seq prompts)
                                      (->> prompts
                                           (map load-prompt)
                                           (remove nil?)
                                           (str/join "\n\n"))
                                      (or (load-prompt "config/prompts/worker.md")
                                          "You are a worker. Claim tasks, execute them, complete them."))]
                   (str task-header "\n"
                        "Task Status: " (:task_status context) "\n"
                        "Pending: " (:pending_tasks context) "\n\n"
                        user-prompts)))

        swarm-id* (or swarm-id "unknown")
        tagged-prompt (str "[oompa:" swarm-id* ":" id "] " prompt)
        abs-worktree (.getAbsolutePath (io/file worktree-path))
        opencode-attach (opencode-attach-url)

        ;; Build command — all harnesses run with cwd=worktree, no sandbox
        ;; so agents can `..` to reach project root for task management
        ;; Claude: --resume flag continues existing session-id conversation
        ;; Opencode: -s/--session + --continue continue existing session
        ;; and --format json for deterministic per-run session capture.
        ;; Codex: no native resume support, always fresh (but worktree state persists)
        cmd (case harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-worktree]
                       model (into ["--model" model])
                       reasoning (into ["-c" (str "reasoning.effort=\"" reasoning "\"")])
                       true (conj "--" tagged-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"
                               "--session-id" session-id]
                        resume? (conj "--resume")
                        model (into ["--model" model]))
              :opencode (cond-> [(resolve-binary! "opencode") "run" "--format" "json"
                                 "--print-logs" "--log-level" "WARN"]
                          model (into ["-m" model])
                          opencode-attach (into ["--attach" opencode-attach])
                          (and resume? session-id) (into ["-s" session-id "--continue"])
                          true (conj tagged-prompt)))

        ;; Run agent — all run with cwd=worktree
        result (try
                 (if (= harness :claude)
                   (process/sh cmd {:dir abs-worktree :in tagged-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-worktree :out :string :err :string}))
                 (catch Exception e
                   (println (format "[%s] Agent exception: %s" id (.getMessage e)))
                   {:exit -1 :out "" :err (.getMessage e)}))
        parsed-opencode (when (= harness :opencode)
                          (parse-opencode-run-output (:out result)))
        output (if (= harness :opencode)
                 (or (:text parsed-opencode) (:out result))
                 (:out result))
        session-id' (if (= harness :opencode)
                      (or (:session-id parsed-opencode) session-id)
                      session-id)]

    {:output output
     :exit (:exit result)
     :done? (agent/done-signal? output)
     :merge? (agent/merge-signal? output)
     :session-id session-id'}))

(defn- run-reviewer!
  "Run reviewer on worktree changes.
   Uses custom review-prompts when configured, otherwise falls back to default.
   prev-feedback: vector of previous review outputs (for multi-round context).
   Returns {:verdict :approved|:needs-changes|:rejected, :comments [...], :output string}"
  [{:keys [id swarm-id review-harness review-model review-prompts]} worktree-path prev-feedback]
  (let [;; Get actual diff content (not just stat) — truncate to 8000 chars for prompt budget
        diff-result (process/sh ["git" "diff" "main"]
                                {:dir worktree-path :out :string :err :string})
        diff-content (let [d (:out diff-result)]
                       (if (> (count d) 8000)
                         (str (subs d 0 8000) "\n... [diff truncated at 8000 chars]")
                         d))

        ;; Build review prompt — use custom prompts if configured, else default
        swarm-id* (or swarm-id "unknown")
        custom-prompt (when (seq review-prompts)
                        (->> review-prompts
                             (map load-prompt)
                             (remove nil?)
                             (str/join "\n\n")))

        ;; Include previous review history for multi-round context
        history-block (when (seq prev-feedback)
                        (str "\n## Previous Review Rounds\n\n"
                             "The worker has already attempted fixes based on earlier feedback. "
                             "Do NOT raise new issues — only verify the original issues are resolved.\n\n"
                             (->> prev-feedback
                                  (map-indexed (fn [i fb]
                                    (str "### Round " (inc i) " feedback:\n" fb)))
                                  (str/join "\n\n"))
                             "\n\n"))

        review-body (str (or custom-prompt
                              (str "Review the changes in this worktree.\n"
                                   "Focus on architecture and design, not style.\n"))
                         "\n\nDiff:\n```\n" diff-content "\n```\n"
                         (when history-block history-block)
                         "\nYour verdict MUST be on its own line, exactly one of:\n"
                         "VERDICT: APPROVED\n"
                         "VERDICT: NEEDS_CHANGES\n"
                         "VERDICT: REJECTED\n")
        review-prompt (str "[oompa:" swarm-id* ":" id "] " review-body)

        abs-wt (.getAbsolutePath (io/file worktree-path))
        opencode-attach (opencode-attach-url)

        ;; Build command — cwd=worktree, no sandbox
        cmd (case review-harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       review-model (into ["--model" review-model])
                       true (conj "--" review-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"]
                        review-model (into ["--model" review-model]))
              :opencode (cond-> [(resolve-binary! "opencode") "run"
                                 "--print-logs" "--log-level" "WARN"]
                          review-model (into ["-m" review-model])
                          opencode-attach (into ["--attach" opencode-attach])
                          true (conj review-prompt)))

        ;; Run reviewer — cwd=worktree
        result (try
                 (if (= review-harness :claude)
                   (process/sh cmd {:dir abs-wt :in review-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-wt :out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))

        output (:out result)

        ;; Parse verdict — require explicit VERDICT: prefix to avoid false matches
        verdict (cond
                  (re-find #"VERDICT:\s*APPROVED" output) :approved
                  (re-find #"VERDICT:\s*REJECTED" output) :rejected
                  (re-find #"VERDICT:\s*NEEDS_CHANGES" output) :needs-changes
                  ;; Fallback to loose matching if reviewer didn't use prefix
                  (re-find #"(?i)\bAPPROVED\b" output) :approved
                  (re-find #"(?i)\bREJECTED\b" output) :rejected
                  :else :needs-changes)]

    ;; Log reviewer output (truncated) for visibility
    (println (format "[%s] Reviewer verdict: %s" id (name verdict)))
    (let [summary (subs output 0 (min 300 (count output)))]
      (println (format "[%s] Review: %s%s" id summary
                       (if (> (count output) 300) "..." ""))))

    {:verdict verdict
     :comments (when (not= (:exit result) 0)
                 [(:err result)])
     :output output}))

(defn- run-fix!
  "Ask worker to fix issues based on reviewer feedback.
   all-feedback: vector of all reviewer outputs so far (accumulated across rounds).
   Returns {:output string, :exit int}"
  [{:keys [id swarm-id harness model]} worktree-path all-feedback]
  (let [swarm-id* (or swarm-id "unknown")
        feedback-text (if (> (count all-feedback) 1)
                        (str "The reviewer has given feedback across " (count all-feedback) " rounds.\n"
                             "Fix ALL outstanding issues:\n\n"
                             (->> all-feedback
                                  (map-indexed (fn [i fb]
                                    (str "--- Round " (inc i) " ---\n" fb)))
                                  (str/join "\n\n")))
                        (str "The reviewer found issues with your changes:\n\n"
                             (first all-feedback)))
        fix-prompt (str "[oompa:" swarm-id* ":" id "] "
                        feedback-text "\n\n"
                        "Fix these issues. Do not add anything the reviewer did not ask for.")

        abs-wt (.getAbsolutePath (io/file worktree-path))
        opencode-attach (opencode-attach-url)

        cmd (case harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       model (into ["--model" model])
                       true (conj "--" fix-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model]))
              :opencode (cond-> [(resolve-binary! "opencode") "run"
                                 "--print-logs" "--log-level" "WARN"]
                          model (into ["-m" model])
                          opencode-attach (into ["--attach" opencode-attach])
                          true (conj fix-prompt)))

        result (try
                 (if (= harness :claude)
                   (process/sh cmd {:dir abs-wt :in fix-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-wt :out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

    {:output (:out result)
     :exit (:exit result)}))

(defn- worktree-has-changes?
  "Check if worktree has committed OR uncommitted changes vs main.
   Workers commit before signaling merge, so we must check both:
   1. Uncommitted changes (git status --porcelain)
   2. Commits ahead of main (git rev-list --count main..HEAD)"
  [wt-path]
  (let [uncommitted (process/sh ["git" "status" "--porcelain"]
                                {:dir wt-path :out :string :err :string})
        ahead (process/sh ["git" "rev-list" "--count" "main..HEAD"]
                          {:dir wt-path :out :string :err :string})
        ahead-count (try (Integer/parseInt (str/trim (:out ahead)))
                         (catch Exception _ 0))]
    (or (not (str/blank? (:out uncommitted)))
        (pos? ahead-count))))

(defn- create-iteration-worktree!
  "Create a fresh worktree for an iteration. Returns {:dir :branch :path}.
   Force-removes stale worktree+branch from previous failed runs first."
  [project-root worker-id iteration]
  (let [wt-dir (format ".w%s-i%d" worker-id iteration)
        wt-branch (format "oompa/%s-i%d" worker-id iteration)
        wt-path (str project-root "/" wt-dir)]
    ;; Clean stale worktree/branch from previous failed runs
    (process/sh ["git" "worktree" "remove" wt-dir "--force"] {:dir project-root})
    (process/sh ["git" "branch" "-D" wt-branch] {:dir project-root})
    (let [result (process/sh ["git" "worktree" "add" wt-dir "-b" wt-branch]
                             {:dir project-root :out :string :err :string})]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "Failed to create worktree: " (:err result))
                        {:dir wt-dir :branch wt-branch}))))
    {:dir wt-dir :branch wt-branch :path wt-path}))

(defn- cleanup-worktree!
  "Remove worktree and branch."
  [project-root wt-dir wt-branch]
  (process/sh ["git" "worktree" "remove" wt-dir "--force"] {:dir project-root})
  (process/sh ["git" "branch" "-D" wt-branch] {:dir project-root}))

(defn- get-head-hash
  "Get the short HEAD commit hash."
  [dir]
  (let [result (process/sh ["git" "rev-parse" "--short" "HEAD"]
                           {:dir dir :out :string :err :string})]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn- annotate-completed-tasks!
  "After a successful merge (called under merge-lock), annotate any tasks in
   complete/ that lack metadata. Adds :completed-by, :completed-at,
   :review-rounds, :merged-commit."
  [project-root worker-id review-rounds]
  (let [commit-hash (get-head-hash project-root)
        complete-dir (io/file project-root "tasks" "complete")]
    (when (.exists complete-dir)
      (doseq [f (.listFiles complete-dir)]
        (when (str/ends-with? (.getName f) ".edn")
          (try
            (let [task (read-string (slurp f))]
              (when-not (:completed-by task)
                (spit f (pr-str (assoc task
                                       :completed-by worker-id
                                       :completed-at (str (java.time.Instant/now))
                                       :review-rounds (or review-rounds 0)
                                       :merged-commit (or commit-hash "unknown"))))))
            (catch Exception e
              (println (format "[%s] Failed to annotate task %s: %s"
                               worker-id (.getName f) (.getMessage e))))))))))

(defn- merge-to-main!
  "Merge worktree changes to main branch. Serialized via merge-lock to prevent
   concurrent workers from corrupting the git index. On success, annotates any
   newly-completed tasks with worker metadata. Returns true on success.
   review-rounds: number of review rounds (0 for auto-merged task-only changes)."
  [wt-path wt-id worker-id project-root review-rounds]
  (locking merge-lock
    (println (format "[%s] Merging changes to main" worker-id))
    (let [;; Commit in worktree if needed (no-op if already committed)
          _ (process/sh ["git" "add" "-A"] {:dir wt-path})
          _ (process/sh ["git" "commit" "-m" (str "Work from " wt-id)]
                        {:dir wt-path})
          ;; Checkout main and merge (in project root, not worktree)
          checkout-result (process/sh ["git" "checkout" "main"]
                                      {:dir project-root :out :string :err :string})
          _ (when-not (zero? (:exit checkout-result))
              (println (format "[%s] MERGE FAILED: could not checkout main: %s"
                               worker-id (:err checkout-result))))
          merge-result (when (zero? (:exit checkout-result))
                         (process/sh ["git" "merge" wt-id "--no-edit"]
                                     {:dir project-root :out :string :err :string}))
          success (and (zero? (:exit checkout-result))
                       (zero? (:exit merge-result)))]
      (if success
        (do
          (println (format "[%s] Merge successful" worker-id))
          ;; Annotate completed tasks while still holding merge-lock
          (annotate-completed-tasks! project-root worker-id review-rounds))
        (when merge-result
          (println (format "[%s] MERGE FAILED: %s" worker-id (:err merge-result)))))
      success)))

(defn- task-only-diff?
  "Check if all changes in worktree are task files only (no code changes).
   Returns true if diff only touches files under tasks/ directory."
  [wt-path]
  (let [result (process/sh ["git" "diff" "main" "--name-only"]
                           {:dir wt-path :out :string :err :string})
        files (when (zero? (:exit result))
                (->> (str/split-lines (:out result))
                     (remove str/blank?)))]
    (and (seq files)
         (every? #(str/starts-with? % "tasks/") files))))

(defn- diff-file-names
  "Get list of changed file names vs main."
  [wt-path]
  (let [result (process/sh ["git" "diff" "main" "--name-only"]
                           {:dir wt-path :out :string :err :string})]
    (when (zero? (:exit result))
      (->> (str/split-lines (:out result))
           (remove str/blank?)
           vec))))

(defn- review-loop!
  "Run review loop: reviewer checks → if issues, fix & retry → back to reviewer.
   Accumulates feedback across rounds so reviewer doesn't raise new issues
   and fixer has full context of all prior feedback.
   Writes review logs to runs/{swarm-id}/reviews/ for post-mortem analysis.
   Returns {:approved? bool, :attempts int}"
  [worker wt-path worker-id iteration]
  (if-not (and (:review-harness worker) (:review-model worker))
    ;; No reviewer configured, auto-approve
    {:approved? true :attempts 0}

    ;; Run review loop with accumulated feedback
    (loop [attempt 1
           prev-feedback []]
      (println (format "[%s] Review attempt %d/%d" worker-id attempt max-review-retries))
      (let [{:keys [verdict output]} (run-reviewer! worker wt-path prev-feedback)
            diff-files (diff-file-names wt-path)]

        ;; Persist review log for this round
        (when (:swarm-id worker)
          (runs/write-review-log! (:swarm-id worker) worker-id iteration attempt
                                  {:verdict verdict
                                   :output output
                                   :diff-files (or diff-files [])}))

        (case verdict
          :approved
          (do
            (println (format "[%s] Reviewer APPROVED (attempt %d)" worker-id attempt))
            {:approved? true :attempts attempt})

          :rejected
          (do
            (println (format "[%s] Reviewer REJECTED (attempt %d)" worker-id attempt))
            {:approved? false :attempts attempt})

          ;; :needs-changes
          (let [all-feedback (conj prev-feedback output)]
            (if (>= attempt max-review-retries)
              (do
                (println (format "[%s] Max review retries reached (%d rounds)" worker-id attempt))
                {:approved? false :attempts attempt})
              (do
                (println (format "[%s] Reviewer requested changes, fixing..." worker-id))
                (run-fix! worker wt-path all-feedback)
                (recur (inc attempt) all-feedback)))))))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

(def ^:private max-wait-for-tasks 60)
(def ^:private wait-poll-interval 5)
(def ^:private max-consecutive-errors 3)

(defn- wait-for-tasks!
  "Wait up to 60s for pending/current tasks to appear. Used for backpressure
   on workers that can't create their own tasks (can_plan: false)."
  [worker-id]
  (loop [waited 0]
    (cond
      (pos? (tasks/pending-count)) true
      (pos? (tasks/current-count)) true
      (>= waited max-wait-for-tasks)
      (do (println (format "[%s] No tasks after %ds, proceeding anyway" worker-id waited))
          false)
      :else
      (do (when (zero? (mod waited 15))
            (println (format "[%s] Waiting for tasks... (%ds)" worker-id waited)))
          (Thread/sleep (* wait-poll-interval 1000))
          (recur (+ waited wait-poll-interval))))))

(defn run-worker!
  "Run worker loop with persistent sessions.

   Sessions persist across iterations — agents resume where they left off.
   Worktrees persist until COMPLETE_AND_READY_FOR_MERGE triggers review+merge.
   __DONE__ stops the worker entirely (planners only).

   Tracks per-worker metrics: merges, rejections, errors, review-rounds-total.
   Returns final worker state with metrics attached."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id iterations]} worker
        project-root (System/getProperty "user.dir")]
    (println (format "[%s] Starting worker (%s:%s%s, %d iterations)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     (if (:reasoning worker) (str ":" (:reasoning worker)) "")
                     iterations))

    ;; Backpressure: workers that can't create tasks wait for tasks to exist
    (when-not (:can-plan worker)
      (wait-for-tasks! id))

    ;; metrics tracks: {:merges N :rejections N :errors N :review-rounds-total N}
    (loop [iter 1
           completed 0
           consec-errors 0
           metrics {:merges 0 :rejections 0 :errors 0 :review-rounds-total 0}
           session-id nil    ;; persistent session-id (nil = start fresh)
           wt-state nil]     ;; {:dir :branch :path} or nil
      (let [finish (fn [status]
                     (assoc worker :completed completed :status status
                                   :merges (:merges metrics)
                                   :rejections (:rejections metrics)
                                   :errors (:errors metrics)
                                   :review-rounds-total (:review-rounds-total metrics)))]
        (if (> iter iterations)
          (do
            ;; Cleanup any lingering worktree
            (when wt-state
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (println (format "[%s] Completed %d iterations (%d merges, %d rejections, %d errors)"
                             id completed (:merges metrics) (:rejections metrics) (:errors metrics)))
            (finish :exhausted))

          ;; Ensure worktree exists (create fresh if nil, reuse if persisted)
          (let [wt-state (try
                           (or wt-state (create-iteration-worktree! project-root id iter))
                           (catch Exception e
                             (println (format "[%s] Worktree creation failed: %s" id (.getMessage e)))
                             nil))]
            (if (nil? wt-state)
              ;; Worktree creation failed — count as error
              (let [errors (inc consec-errors)
                    metrics (update metrics :errors inc)]
                (if (>= errors max-consecutive-errors)
                  (do
                    (println (format "[%s] %d consecutive errors, stopping" id errors))
                    (finish :error))
                  (recur (inc iter) completed errors metrics nil nil)))

              ;; Worktree ready — run agent
              (let [resume? (some? session-id)
                    _ (println (format "[%s] %s iteration %d/%d"
                                       id (if resume? "Resuming" "Starting") iter iterations))
                    context (build-context)
                    {:keys [output exit done? merge?] :as agent-result}
                    (run-agent! worker (:path wt-state) context session-id resume?)
                    new-session-id (:session-id agent-result)]

                (cond
                  ;; Agent errored — cleanup, reset session
                  (not (zero? exit))
                  (let [errors (inc consec-errors)
                        metrics (update metrics :errors inc)]
                    (println (format "[%s] Agent error (exit %d): %s"
                                     id exit (subs (or output "") 0 (min 200 (count (or output ""))))))
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (if (>= errors max-consecutive-errors)
                      (do
                        (println (format "[%s] %d consecutive errors, stopping" id errors))
                        (finish :error))
                      (recur (inc iter) completed errors metrics nil nil)))

                  ;; COMPLETE_AND_READY_FOR_MERGE — review, merge, reset session
                  merge?
                  (if (worktree-has-changes? (:path wt-state))
                    (if (task-only-diff? (:path wt-state))
                      ;; Task-only changes — skip review, auto-merge
                      (do
                        (println (format "[%s] Task-only diff, auto-merging" id))
                        (let [merged? (merge-to-main! (:path wt-state) (:branch wt-state) id project-root 0)
                              metrics (if merged? (update metrics :merges inc) metrics)]
                          (println (format "[%s] Iteration %d/%d complete" id iter iterations))
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (if (and done? (:can-plan worker))
                            (do
                              (println (format "[%s] Worker done after merge" id))
                              (assoc worker :completed (inc completed) :status :done
                                            :merges (:merges metrics)
                                            :rejections (:rejections metrics)
                                            :errors (:errors metrics)
                                            :review-rounds-total (:review-rounds-total metrics)))
                            (recur (inc iter) (inc completed) 0 metrics nil nil))))
                      ;; Code changes — full review loop
                      (let [{:keys [approved? attempts]} (review-loop! worker (:path wt-state) id iter)
                            metrics (-> metrics
                                        (update :review-rounds-total + (or attempts 0))
                                        (update (if approved? :merges :rejections) inc))]
                        (if approved?
                          (do
                            (merge-to-main! (:path wt-state) (:branch wt-state) id project-root (or attempts 0))
                            (println (format "[%s] Iteration %d/%d complete" id iter iterations))
                            (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                            ;; If also __DONE__, stop after merge
                            (if (and done? (:can-plan worker))
                              (do
                                (println (format "[%s] Worker done after merge" id))
                                (assoc worker :completed (inc completed) :status :done
                                              :merges (:merges metrics)
                                              :rejections (:rejections metrics)
                                              :errors (:errors metrics)
                                              :review-rounds-total (:review-rounds-total metrics)))
                              (recur (inc iter) (inc completed) 0 metrics nil nil)))
                          (do
                            (println (format "[%s] Iteration %d/%d rejected" id iter iterations))
                            (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                            (recur (inc iter) completed 0 metrics nil nil)))))
                    (do
                      (println (format "[%s] Merge signaled but no changes, skipping" id))
                      (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                      (recur (inc iter) completed 0 metrics nil nil)))

                  ;; __DONE__ without merge — only honor for planners
                  (and done? (:can-plan worker))
                  (do
                    (println (format "[%s] Received __DONE__ signal" id))
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (println (format "[%s] Worker done after %d/%d iterations" id iter iterations))
                    (finish :done))

                  ;; __DONE__ from executor — ignore signal, but reset session since
                  ;; the agent process exited. Resuming a dead session causes exit 1
                  ;; which cascades into consecutive errors and premature stopping.
                  (and done? (not (:can-plan worker)))
                  (do
                    (println (format "[%s] Ignoring __DONE__ (executor), resetting session" id))
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (recur (inc iter) completed 0 metrics nil nil))

                  ;; No signal — agent still working, resume next iteration
                  :else
                  (do
                    (println (format "[%s] Working... (will resume)" id))
                    (recur (inc iter) completed 0 metrics new-session-id wt-state)))))))))))

;; =============================================================================
;; Multi-Worker Execution
;; =============================================================================

(defn run-workers!
  "Run multiple workers in parallel.
   Writes swarm summary to runs/{swarm-id}/summary.edn on completion.

   Arguments:
     workers - seq of worker configs

   Returns seq of final worker states."
  [workers]
  (tasks/ensure-dirs!)
  (let [swarm-id (-> workers first :swarm-id)]
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
          (println (format "  [%s] %s - %d completed, %d merges, %d rejections, %d errors, %d review rounds"
                           (:id w)
                           (name (:status w))
                           (:completed w)
                           (or (:merges w) 0)
                           (or (:rejections w) 0)
                           (or (:errors w) 0)
                           (or (:review-rounds-total w) 0))))

        ;; Write swarm summary to disk
        (when swarm-id
          (runs/write-summary! swarm-id results)
          (println (format "\nSwarm summary written to runs/%s/summary.edn" swarm-id)))

        results))))

;; =============================================================================
;; Planner — first-class config concept, NOT a worker
;; =============================================================================
;; The planner creates task EDN files in tasks/pending/.
;; It runs in the project root (no worktree), has no review/merge cycle,
;; and respects max_pending backpressure to avoid flooding the queue.

(defn run-planner!
  "Run planner agent to create tasks. No worktree, no review, no merge.
   Runs in project root. Respects max_pending cap.
   Returns {:tasks-created N}"
  [{:keys [harness model prompts max-pending swarm-id]}]
  (tasks/ensure-dirs!)
  (let [project-root (System/getProperty "user.dir")
        pending-before (tasks/pending-count)
        max-pending (or max-pending 10)]
    ;; Backpressure: skip if queue is full
    (if (>= pending-before max-pending)
      (do
        (println (format "[planner] Skipping — %d pending tasks (max: %d)" pending-before max-pending))
        {:tasks-created 0})
      ;; Run agent
      (let [context (build-context)
            prompt-text (str (when (seq prompts)
                               (->> prompts
                                    (map load-prompt)
                                    (remove nil?)
                                    (str/join "\n\n")))
                             "\n\nTask Status: " (:task_status context) "\n"
                             "Pending: " (:pending_tasks context) "\n\n"
                             "Create tasks in tasks/pending/ as .edn files.\n"
                             "Maximum " (- max-pending pending-before) " new tasks.\n"
                             "Signal __DONE__ when finished planning.")
            swarm-id* (or swarm-id "unknown")
            tagged-prompt (str "[oompa:" swarm-id* ":planner] " prompt-text)
            abs-root (.getAbsolutePath (io/file project-root))
            opencode-attach (opencode-attach-url)

            cmd (case harness
                  :codex (cond-> [(resolve-binary! "codex") "exec"
                                  "--dangerously-bypass-approvals-and-sandbox"
                                  "--skip-git-repo-check"
                                  "-C" abs-root]
                           model (into ["--model" model])
                           true (conj "--" tagged-prompt))
                  :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"]
                            model (into ["--model" model]))
                  :opencode (cond-> [(resolve-binary! "opencode") "run"
                                     "--print-logs" "--log-level" "WARN"]
                              model (into ["-m" model])
                              opencode-attach (into ["--attach" opencode-attach])
                              true (conj tagged-prompt)))

            _ (println (format "[planner] Running (%s:%s, max_pending: %d, current: %d)"
                               (name harness) (or model "default") max-pending pending-before))

            result (try
                     (if (= harness :claude)
                       (process/sh cmd {:dir abs-root :in tagged-prompt :out :string :err :string})
                       (process/sh cmd {:dir abs-root :out :string :err :string}))
                     (catch Exception e
                       (println (format "[planner] Agent exception: %s" (.getMessage e)))
                       {:exit -1 :out "" :err (.getMessage e)}))

            ;; Commit any new task files
            _ (process/sh ["git" "add" "tasks/pending/"] {:dir abs-root})
            _ (process/sh ["git" "commit" "-m" "Planner: add tasks"]
                          {:dir abs-root :out :string :err :string})

            pending-after (tasks/pending-count)
            created (- pending-after pending-before)]

        (println (format "[planner] Done. Created %d tasks (pending: %d)" created pending-after))
        {:tasks-created created}))))
