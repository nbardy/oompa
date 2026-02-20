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
            [agentnet.core :as core]
            [agentnet.harness :as harness]
            [agentnet.worktree :as worktree]
            [agentnet.runs :as runs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.set]
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

;; Set by JVM shutdown hook (SIGTERM/SIGINT). Workers check this between cycles
;; and exit gracefully — finishing the current cycle before stopping.
(def ^:private shutdown-requested? (atom false))

(defn- load-prompt
  "Load a prompt file. Tries path as-is first, then from package root."
  [path]
  (or (agent/load-custom-prompt path)
      (agent/load-custom-prompt (str package-root "/" path))))

(defn- build-template-tokens
  "Build token map for prompt template {var} substitution.
   Merges core/build-context (rich YAML header, queue, hotspots, etc.)
   with worker-level context (task_status, pending_tasks) and defaults
   for tokens that core/build-context doesn't produce (mode_hint, targets,
   recent_sec). Without these defaults, those {vars} leak into prompts."
  [worker-context]
  (let [pending (tasks/list-pending)
        core-ctx (core/build-context {:tasks pending
                                      :repo (System/getProperty "user.dir")})]
    (merge {:mode_hint "propose"
            :targets "*"
            :recent_sec "180"}
           core-ctx
           worker-context)))

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
   :review-prompts paths to reviewer prompt files (loaded and concatenated for review).
   :wait-between seconds to sleep between iterations (nil or 0 = no wait)."
  [{:keys [id swarm-id harness model iterations prompts can-plan reasoning
           review-harness review-model review-prompts wait-between]}]
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
   :wait-between (when (and wait-between (pos? wait-between)) wait-between)
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


(defn- execute-claims!
  "Execute CLAIM signal: attempt to claim each task ID from pending/.
   Returns {:claimed [ids], :failed [ids], :resume-prompt string}."
  [claim-ids]
  (let [results (tasks/claim-by-ids! claim-ids)
        claimed (filterv #(= :claimed (:status %)) results)
        failed (filterv #(not= :claimed (:status %)) results)
        claimed-ids (mapv :id claimed)
        failed-ids (mapv :id failed)
        context (build-context)
        prompt (str "## Claim Results\n"
                    (if (seq claimed-ids)
                      (str "Claimed: " (str/join ", " claimed-ids) "\n")
                      "No tasks were successfully claimed.\n")
                    (when (seq failed-ids)
                      (str "Already taken or not found: "
                           (str/join ", " failed-ids) "\n"))
                    "\nTask Status: " (:task_status context) "\n"
                    "Remaining Pending:\n"
                    (if (str/blank? (:pending_tasks context))
                      "(none)"
                      (:pending_tasks context))
                    "\n\n"
                    (if (seq claimed-ids)
                      "Work on your claimed tasks. Signal COMPLETE_AND_READY_FOR_MERGE when done."
                      "No claims succeeded. CLAIM different tasks, or signal __DONE__ if no suitable work remains."))]
    {:claimed claimed-ids
     :failed failed-ids
     :resume-prompt prompt}))

(defn- run-agent!
  "Run agent with prompt, return {:output :done? :merge? :claim-ids :exit :session-id}.
   When resume? is true, continues the existing session with a lighter prompt.
   resume-prompt-override: when non-nil, replaces the default resume prompt
   (used to inject CLAIM results). All harness-specific CLI knowledge
   is delegated to harness/build-cmd."
  [{:keys [id swarm-id harness model prompts reasoning]} worktree-path context session-id resume?
   & {:keys [resume-prompt-override]}]
  (let [session-id (or session-id (harness/make-session-id harness))

        ;; Build prompt — 3-way: override → standard resume → fresh start
        prompt (cond
                 ;; CLAIM results or other injected resume prompt
                 resume-prompt-override
                 resume-prompt-override

                 ;; Standard resume — lighter (agent already has full context)
                 resume?
                 (str "Task Status: " (:task_status context) "\n"
                      "Pending: " (:pending_tasks context) "\n\n"
                      "Continue working. Signal COMPLETE_AND_READY_FOR_MERGE when your current task is done and ready for review.")

                 ;; Fresh start — full task header + tokenized user prompts
                 ;; Template tokens ({context_header}, {queue_md}, etc.) are
                 ;; replaced here. Without this, raw {var} placeholders leak
                 ;; into the agent prompt verbatim.
                 :else
                 (let [task-header (render-task-header
                                     (load-prompt "config/prompts/_task_header.md")
                                     worktree-path)
                       template-tokens (build-template-tokens context)
                       user-prompts (if (seq prompts)
                                      (->> prompts
                                           (map load-prompt)
                                           (remove nil?)
                                           (map #(agent/tokenize % template-tokens))
                                           (str/join "\n\n"))
                                      (or (some-> (load-prompt "config/prompts/worker.md")
                                                  (agent/tokenize template-tokens))
                                          "You are a worker. Claim tasks, execute them, complete them."))]
                   (str task-header "\n"
                        "Task Status: " (:task_status context) "\n"
                        "Pending: " (:pending_tasks context) "\n\n"
                        user-prompts)))

        swarm-id* (or swarm-id "unknown")
        tagged-prompt (str "[oompa:" swarm-id* ":" id "] " prompt)
        abs-worktree (.getAbsolutePath (io/file worktree-path))

        cmd (harness/build-cmd harness
              {:cwd abs-worktree :model model :reasoning reasoning
               :session-id session-id :resume? resume?
               :prompt tagged-prompt :format? true})

        result (try
                 (process/sh cmd {:dir abs-worktree
                                  :in (harness/process-stdin harness tagged-prompt)
                                  :out :string :err :string})
                 (catch Exception e
                   (println (format "[%s] Agent exception: %s" id (.getMessage e)))
                   {:exit -1 :out "" :err (.getMessage e)}))

        {:keys [output session-id]}
        (harness/parse-output harness (:out result) session-id)]

    {:output output
     :exit (:exit result)
     :done? (agent/done-signal? output)
     :merge? (agent/merge-signal? output)
     :claim-ids (agent/parse-claim-signal output)
     :session-id session-id}))

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

        swarm-id* (or swarm-id "unknown")
        custom-prompt (when (seq review-prompts)
                        (->> review-prompts
                             (map load-prompt)
                             (remove nil?)
                             (str/join "\n\n")))

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

        ;; No session, no resume, no format flags — reviewer is stateless one-shot
        cmd (harness/build-cmd review-harness
              {:cwd abs-wt :model review-model :prompt review-prompt})

        result (try
                 (process/sh cmd {:dir abs-wt
                                  :in (harness/process-stdin review-harness review-prompt)
                                  :out :string :err :string})
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))

        output (:out result)

        ;; Parse verdict — require explicit VERDICT: prefix to avoid false matches
        verdict (cond
                  (re-find #"VERDICT:\s*APPROVED" output) :approved
                  (re-find #"VERDICT:\s*REJECTED" output) :rejected
                  (re-find #"VERDICT:\s*NEEDS_CHANGES" output) :needs-changes
                  (re-find #"(?i)\bAPPROVED\b" output) :approved
                  (re-find #"(?i)\bREJECTED\b" output) :rejected
                  :else :needs-changes)]

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

        cmd (harness/build-cmd harness
              {:cwd abs-wt :model model :prompt fix-prompt})

        result (try
                 (process/sh cmd {:dir abs-wt
                                  :in (harness/process-stdin harness fix-prompt)
                                  :out :string :err :string})
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

    {:output (:out result)
     :exit (:exit result)}))

(defn- sync-worktree-to-main!
  "Merge latest main into worktree branch before merge-to-main!.
   If conflicts arise, launches a one-shot agent to resolve them.
   Runs OUTSIDE the merge-lock so the agent doesn't block other workers.
   Returns :synced | :resolved | :failed."
  [worker wt-path worker-id]
  (let [merge-result (process/sh ["git" "merge" "main" "--no-edit"]
                                 {:dir wt-path :out :string :err :string})]
    (if (zero? (:exit merge-result))
      (do (println (format "[%s] Worktree synced to main" worker-id))
          :synced)
      ;; Conflict — launch agent to resolve in the worktree
      (let [_ (println (format "[%s] Sync conflict, launching resolver" worker-id))
            conflict-files (:out (process/sh ["git" "diff" "--name-only" "--diff-filter=U"]
                                             {:dir wt-path :out :string :err :string}))
            resolve-prompt (str "[oompa:" (or (:swarm-id worker) "unknown") ":" worker-id "] "
                                "Merge conflicts when syncing to main.\n"
                                "Conflicted files:\n" conflict-files "\n\n"
                                "Resolve ALL conflicts. Keep intent of both sides. "
                                "Stage resolved files with git add.")
            abs-wt (.getAbsolutePath (io/file wt-path))
            cmd (harness/build-cmd (:harness worker)
                  {:cwd abs-wt :model (:model worker) :prompt resolve-prompt})
            result (try
                     (process/sh cmd {:dir abs-wt
                                      :in (harness/process-stdin (:harness worker) resolve-prompt)
                                      :out :string :err :string})
                     (catch Exception e
                       {:exit -1 :out "" :err (.getMessage e)}))]
        (if (zero? (:exit result))
          ;; Agent ran — check if conflicts are actually resolved
          (let [_ (process/sh ["git" "add" "-A"] {:dir wt-path})
                remaining (:out (process/sh ["git" "diff" "--name-only" "--diff-filter=U"]
                                            {:dir wt-path :out :string :err :string}))
                clean? (str/blank? remaining)]
            (if clean?
              (let [commit (process/sh ["git" "commit" "--no-edit"]
                                       {:dir wt-path :out :string :err :string})]
                (if (zero? (:exit commit))
                  (do (println (format "[%s] Conflicts resolved by agent" worker-id))
                      :resolved)
                  (do (println (format "[%s] Commit after resolution failed" worker-id))
                      (process/sh ["git" "merge" "--abort"] {:dir wt-path})
                      :failed)))
              (do (println (format "[%s] Agent left unresolved conflicts" worker-id))
                  (process/sh ["git" "merge" "--abort"] {:dir wt-path})
                  :failed)))
          (do (println (format "[%s] Resolver agent failed (exit %d)" worker-id (:exit result)))
              (process/sh ["git" "merge" "--abort"] {:dir wt-path})
              :failed))))))

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

(defn- detect-claimed-tasks
  "Diff current/ task IDs before and after agent ran.
   Returns set of task IDs this worker claimed during iteration."
  [pre-current-ids]
  (let [post-ids (tasks/current-task-ids)]
    (clojure.set/difference post-ids pre-current-ids)))

(defn- emit-cycle-log!
  "Write cycle event log. Called at every cycle exit point.
   session-id links to the Claude CLI conversation transcript on disk.
   No mutable summary state — all state is derived from immutable cycle logs."
  [swarm-id worker-id cycle start-ms session-id
   {:keys [outcome claimed-task-ids recycled-tasks error-snippet review-rounds]}]
  (let [duration-ms (- (System/currentTimeMillis) start-ms)]
    (runs/write-cycle-log!
      swarm-id worker-id cycle
      {:outcome outcome
       :duration-ms duration-ms
       :claimed-task-ids (vec (or claimed-task-ids []))
       :recycled-tasks (or recycled-tasks [])
       :error-snippet error-snippet
       :review-rounds (or review-rounds 0)
       :session-id session-id})))

(defn- recycle-orphaned-tasks!
  "Recycle tasks that a worker claimed but didn't complete.
   Compares current/ task IDs before and after the agent ran —
   new IDs that appeared are tasks this worker claimed. On failure
   or rejection, move them back to pending/ so other workers can
   pick them up. Returns count of recycled tasks."
  [worker-id pre-current-ids]
  (let [post-current-ids (tasks/current-task-ids)
        orphaned-ids (clojure.set/difference post-current-ids pre-current-ids)
        recycled (when (seq orphaned-ids)
                   (tasks/recycle-tasks! orphaned-ids))]
    (when (seq recycled)
      (println (format "[%s] Recycled %d orphaned task(s): %s"
                       worker-id (count recycled) (str/join ", " recycled))))
    (count (or recycled []))))

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
   concurrent workers from corrupting the git index. On success, moves claimed
   tasks current→complete and annotates metadata. Returns true on success.
   claimed-task-ids: set of task IDs this worker claimed (framework owns completion)."
  [wt-path wt-id worker-id project-root review-rounds claimed-task-ids]
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
          ;; Framework-owned completion: move claimed tasks current→complete
          (when (seq claimed-task-ids)
            (let [completed (tasks/complete-by-ids! claimed-task-ids)]
              (when (seq completed)
                (println (format "[%s] Completed %d task(s): %s"
                                 worker-id (count completed) (str/join ", " completed))))))
          ;; Annotate completed tasks with metadata while still holding merge-lock
          (annotate-completed-tasks! project-root worker-id review-rounds))
        ;; FAILED: Clean up git state before releasing merge-lock.
        ;; Without this, a conflict leaves .git/MERGE_HEAD and poisons the
        ;; shared index — every subsequent worker fails on `git checkout main`.
        (do
          (println (format "[%s] MERGE FAILED: %s" worker-id
                           (or (:err merge-result) (:err checkout-result))))
          (let [abort-result (process/sh ["git" "merge" "--abort"]
                                         {:dir project-root :out :string :err :string})]
            (when-not (zero? (:exit abort-result))
              ;; Abort failed (no merge in progress, or other issue) — hard reset.
              (process/sh ["git" "reset" "--hard" "HEAD"]
                          {:dir project-root :out :string :err :string})))))
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

(defn- maybe-sleep-between!
  "Sleep between iterations when wait-between is configured.
   Called at the start of each iteration (except the first)."
  [worker-id wait-between iter]
  (when (and wait-between (> iter 1))
    (println (format "[%s] Sleeping %ds before next iteration" worker-id wait-between))
    (Thread/sleep (* wait-between 1000))))

(defn run-worker!
  "Run worker loop with persistent sessions.

   Sessions persist across iterations — agents resume where they left off.
   Worktrees persist until COMPLETE_AND_READY_FOR_MERGE triggers review+merge.
   __DONE__ stops the worker entirely (planners only).

   Tracks per-worker metrics: merges, rejections, errors, review-rounds-total.
   Returns final worker state with metrics attached."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id iterations swarm-id wait-between]} worker
        project-root (System/getProperty "user.dir")]
    (println (format "[%s] Starting worker (%s:%s%s, %d iterations%s)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     (if (:reasoning worker) (str ":" (:reasoning worker)) "")
                     iterations
                     (if wait-between (format ", %ds between" wait-between) "")))

    ;; Backpressure: workers that can't create tasks wait for tasks to exist
    (when-not (:can-plan worker)
      (wait-for-tasks! id))

    ;; metrics tracks: {:merges N :rejections N :errors N :recycled N :review-rounds-total N :claims N}
    (loop [iter 1
           completed 0
           consec-errors 0
           metrics {:merges 0 :rejections 0 :errors 0 :recycled 0 :review-rounds-total 0 :claims 0}
           session-id nil            ;; persistent session-id (nil = start fresh)
           wt-state nil              ;; {:dir :branch :path} or nil
           claimed-ids #{}           ;; task IDs claimed this session (reset on worktree destroy)
           claim-resume-prompt nil]  ;; override prompt for next iteration (from CLAIM results)
      (let [finish (fn [status]
                     (assoc worker :completed completed :status status
                                   :merges (:merges metrics)
                                   :rejections (:rejections metrics)
                                   :errors (:errors metrics)
                                   :recycled (:recycled metrics)
                                   :review-rounds-total (:review-rounds-total metrics)
                                   :claims (:claims metrics)))]
        (cond
          (> iter iterations)
          (do
            ;; Cleanup any lingering worktree
            (when wt-state
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (println (format "[%s] Completed %d iterations (%d merges, %d claims, %d rejections, %d errors, %d recycled)"
                             id completed (:merges metrics) (:claims metrics) (:rejections metrics) (:errors metrics) (:recycled metrics)))
            (finish :exhausted))

          @shutdown-requested?
          (do
            (println (format "[%s] Shutdown requested, stopping after %d iterations" id (dec iter)))
            (when wt-state
              ;; Recycle any claimed tasks back to pending so other workers can pick them up
              (when (seq claimed-ids)
                (let [recycled (tasks/recycle-tasks! claimed-ids)]
                  (when (seq recycled)
                    (println (format "[%s] Recycled %d claimed task(s) on shutdown" id (count recycled))))))
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (emit-cycle-log! swarm-id id iter (System/currentTimeMillis) session-id
              {:outcome :interrupted})
            (finish :interrupted))

          :else
          (do
          ;; Sleep between iterations when wait_between is configured
          (maybe-sleep-between! id wait-between iter)

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
                  (recur (inc iter) completed errors metrics nil nil #{} nil)))

              ;; Worktree ready — run agent
              (let [resume? (or (some? session-id) (some? claim-resume-prompt))
                    iter-start-ms (System/currentTimeMillis)
                    ;; Snapshot current/ task IDs before agent runs so we can
                    ;; detect any direct mv claims (safety net for old behavior).
                    pre-current-ids (tasks/current-task-ids)
                    _ (println (format "[%s] %s iteration %d/%d"
                                       id (if resume? "Resuming" "Starting") iter iterations))
                    context (build-context)
                    {:keys [output exit done? merge? claim-ids] :as agent-result}
                    (run-agent! worker (:path wt-state) context session-id resume?
                                :resume-prompt-override claim-resume-prompt)
                    new-session-id (:session-id agent-result)
                    ;; Safety net: detect any direct mv claims (old behavior)
                    mv-claimed-tasks (detect-claimed-tasks pre-current-ids)]

                (cond
                  ;; Agent errored — recycle claimed tasks, cleanup, reset session
                  (not (zero? exit))
                  (let [errors (inc consec-errors)
                        recycled (recycle-orphaned-tasks! id pre-current-ids)
                        metrics (-> metrics
                                    (update :errors inc)
                                    (update :recycled + recycled))
                        error-msg (subs (or output "") 0 (min 200 (count (or output ""))))]
                    (println (format "[%s] Agent error (exit %d): %s" id exit error-msg))
                    (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                      {:outcome :error :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))
                       :recycled-tasks (when (pos? recycled) (vec mv-claimed-tasks))
                       :error-snippet error-msg})
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (if (>= errors max-consecutive-errors)
                      (do
                        (println (format "[%s] %d consecutive errors, stopping" id errors))
                        (finish :error))
                      (recur (inc iter) completed errors metrics nil nil #{} nil)))

                  ;; CLAIM signal — framework claims tasks, resumes agent with results
                  ;; Only honored when no MERGE or DONE signal (lowest priority)
                  (and (seq claim-ids) (not merge?) (not done?))
                  (let [_ (println (format "[%s] CLAIM signal: %s" id (str/join ", " claim-ids)))
                        {:keys [claimed failed resume-prompt]} (execute-claims! claim-ids)
                        new-claimed-ids (into claimed-ids claimed)
                        metrics (update metrics :claims + (count claimed))]
                    (println (format "[%s] Claimed %d/%d tasks" id (count claimed) (count claim-ids)))
                    (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                      {:outcome :claimed :claimed-task-ids (vec claimed)})
                    (recur (inc iter) completed 0 metrics new-session-id wt-state
                           new-claimed-ids resume-prompt))

                  ;; COMPLETE_AND_READY_FOR_MERGE — review, merge, reset session
                  merge?
                  (if (worktree-has-changes? (:path wt-state))
                    (if (task-only-diff? (:path wt-state))
                      ;; Task-only changes — skip review, sync to main, auto-merge
                      (do
                        (println (format "[%s] Task-only diff, auto-merging" id))
                        (let [sync-status (sync-worktree-to-main! worker (:path wt-state) id)
                              all-claimed (into claimed-ids mv-claimed-tasks)]
                          (if (= :failed sync-status)
                            ;; Sync failed — cannot merge safely, skip
                            (do
                              (println (format "[%s] Sync to main failed, skipping merge" id))
                              (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                                {:outcome :sync-failed :claimed-task-ids (vec all-claimed)})
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (recur (inc iter) completed 0 metrics nil nil #{} nil))
                            ;; Synced — proceed with merge
                            (let [merged? (merge-to-main! (:path wt-state) (:branch wt-state) id project-root 0 all-claimed)
                                  metrics (if merged? (update metrics :merges inc) metrics)]
                              (println (format "[%s] Cycle %d/%d complete" id iter iterations))
                              (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                                {:outcome :merged :claimed-task-ids (vec all-claimed) :review-rounds 0})
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (if (and done? (:can-plan worker))
                                (do
                                  (println (format "[%s] Worker done after merge" id))
                                  (assoc worker :completed (inc completed) :status :done
                                                :merges (:merges metrics)
                                                :rejections (:rejections metrics)
                                                :errors (:errors metrics)
                                                :recycled (:recycled metrics)
                                                :review-rounds-total (:review-rounds-total metrics)
                                                :claims (:claims metrics)))
                                (recur (inc iter) (inc completed) 0 metrics nil nil #{} nil))))))
                      ;; Code changes — full review loop
                      (let [{:keys [approved? attempts]} (review-loop! worker (:path wt-state) id iter)
                            ;; Don't pre-increment :merges — defer to after actual merge succeeds
                            metrics (-> metrics
                                        (update :review-rounds-total + (or attempts 0))
                                        (cond-> (not approved?) (update :rejections inc)))]
                        (if approved?
                          (let [sync-status (sync-worktree-to-main! worker (:path wt-state) id)
                                all-claimed (into claimed-ids mv-claimed-tasks)]
                            (if (= :failed sync-status)
                              ;; Sync failed after approval — treat as sync failure, skip merge
                              (do
                                (println (format "[%s] Sync to main failed after approval, skipping merge" id))
                                (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                                  {:outcome :sync-failed :claimed-task-ids (vec all-claimed)
                                   :review-rounds (or attempts 0)})
                                (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                                (recur (inc iter) completed 0 metrics nil nil #{} nil))
                              ;; Synced — proceed with merge, capture return value
                              (let [merged? (merge-to-main! (:path wt-state) (:branch wt-state) id project-root (or attempts 0) all-claimed)
                                    metrics (if merged? (update metrics :merges inc) metrics)]
                                (println (format "[%s] Cycle %d/%d complete" id iter iterations))
                                (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                                  {:outcome (if merged? :merged :merge-failed)
                                   :claimed-task-ids (vec all-claimed)
                                   :review-rounds (or attempts 0)})
                                (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                                ;; If also __DONE__, stop after merge
                                (if (and done? (:can-plan worker))
                                  (do
                                    (println (format "[%s] Worker done after merge" id))
                                    (assoc worker :completed (inc completed) :status :done
                                                  :merges (:merges metrics)
                                                  :rejections (:rejections metrics)
                                                  :errors (:errors metrics)
                                                  :recycled (:recycled metrics)
                                                  :review-rounds-total (:review-rounds-total metrics)
                                                  :claims (:claims metrics)))
                                  (recur (inc iter) (inc completed) 0 metrics nil nil #{} nil)))))
                          (let [recycled (recycle-orphaned-tasks! id pre-current-ids)
                                metrics (update metrics :recycled + recycled)]
                            (println (format "[%s] Cycle %d/%d rejected" id iter iterations))
                            (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                              {:outcome :rejected :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))
                               :recycled-tasks (when (pos? recycled) (vec mv-claimed-tasks))
                               :review-rounds (or attempts 0)})
                            (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                            (recur (inc iter) completed 0 metrics nil nil #{} nil)))))
                    (let [recycled (recycle-orphaned-tasks! id pre-current-ids)
                          metrics (update metrics :recycled + recycled)]
                      (println (format "[%s] Merge signaled but no changes, skipping" id))
                      (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                        {:outcome :no-changes :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))
                         :recycled-tasks (when (pos? recycled) (vec mv-claimed-tasks))})
                      (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                      (recur (inc iter) completed 0 metrics nil nil #{} nil)))

                  ;; __DONE__ without merge — only honor for planners
                  (and done? (:can-plan worker))
                  (do
                    (println (format "[%s] Received __DONE__ signal" id))
                    (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                      {:outcome :done :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))})
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (println (format "[%s] Worker done after %d/%d iterations" id iter iterations))
                    (finish :done))

                  ;; __DONE__ from executor — ignore signal, but reset session since
                  ;; the agent process exited. Resuming a dead session causes exit 1
                  ;; which cascades into consecutive errors and premature stopping.
                  (and done? (not (:can-plan worker)))
                  (let [recycled (recycle-orphaned-tasks! id pre-current-ids)
                        metrics (update metrics :recycled + recycled)]
                    (println (format "[%s] Ignoring __DONE__ (executor), resetting session" id))
                    (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                      {:outcome :executor-done :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))
                       :recycled-tasks (when (pos? recycled) (vec mv-claimed-tasks))})
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (recur (inc iter) completed 0 metrics nil nil #{} nil))

                  ;; No signal — agent still working, resume next iteration
                  :else
                  (do
                    (println (format "[%s] Working... (will resume)" id))
                    (emit-cycle-log! swarm-id id iter iter-start-ms new-session-id
                      {:outcome :working :claimed-task-ids (vec (into claimed-ids mv-claimed-tasks))})
                    (recur (inc iter) completed 0 metrics new-session-id wt-state
                           claimed-ids nil))))))))))))

;; =============================================================================
;; Multi-Worker Execution
;; =============================================================================

(defn run-workers!
  "Run multiple workers in parallel.
   Writes stopped event to runs/{swarm-id}/stopped.json on completion.

   Arguments:
     workers - seq of worker configs

   Returns seq of final worker states."
  [workers]
  (tasks/ensure-dirs!)
  (let [swarm-id (-> workers first :swarm-id)]
    (println (format "Launching %d workers..." (count workers)))

    ;; Register JVM shutdown hook so SIGTERM/SIGINT triggers graceful stop.
    ;; Sets the shutdown atom — workers check it between cycles and exit cleanly.
    ;; The hook waits for workers to finish, then writes stopped.json only if
    ;; the clean exit path hasn't already done so (guarded by the atom).
    (let [hook (Thread. (fn []
                          (println "\nShutdown signal received, stopping workers after current cycle...")
                          (reset! shutdown-requested? true)
                          ;; Give workers time to finish current cycle and cleanup.
                          ;; After sleep, write stopped.json only if still in shutdown
                          ;; (clean exit resets the atom to false before writing :completed).
                          (Thread/sleep 10000)
                          (when (and swarm-id @shutdown-requested?)
                            (runs/write-stopped! swarm-id :interrupted))))]
      (.addShutdownHook (Runtime/getRuntime) hook)

      (let [futures (doall
                      (map-indexed
                        (fn [idx worker]
                          (let [worker (assoc worker :id (or (:id worker) (str "w" idx)))]
                            (future (run-worker! worker))))
                        workers))]

        (println "All workers launched. Waiting for completion...")
        (let [results (mapv deref futures)]
          ;; Clean exit — tell shutdown hook not to write stopped.json
          (reset! shutdown-requested? false)
          ;; Remove the hook so it doesn't accumulate across calls
          (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch Exception _))
          (println "\nAll workers complete.")
          (doseq [w results]
            (println (format "  [%s] %s - %d completed, %d merges, %d claims, %d rejections, %d errors, %d recycled, %d review rounds"
                             (:id w)
                             (name (:status w))
                             (:completed w)
                             (or (:merges w) 0)
                             (or (:claims w) 0)
                             (or (:rejections w) 0)
                             (or (:errors w) 0)
                             (or (:recycled w) 0)
                             (or (:review-rounds-total w) 0))))

          ;; Write stopped event — all state derivable from cycle logs
          (when swarm-id
            (runs/write-stopped! swarm-id :completed)
            (println (format "\nStopped event written to runs/%s/stopped.json" swarm-id)))

          results)))))

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
            template-tokens (build-template-tokens context)
            prompt-text (str (when (seq prompts)
                               (->> prompts
                                    (map load-prompt)
                                    (remove nil?)
                                    (map #(agent/tokenize % template-tokens))
                                    (str/join "\n\n")))
                             "\n\nTask Status: " (:task_status context) "\n"
                             "Pending: " (:pending_tasks context) "\n\n"
                             "Create tasks in tasks/pending/ as .edn files.\n"
                             "Maximum " (- max-pending pending-before) " new tasks.\n"
                             "Signal __DONE__ when finished planning.")
            swarm-id* (or swarm-id "unknown")
            tagged-prompt (str "[oompa:" swarm-id* ":planner] " prompt-text)
            abs-root (.getAbsolutePath (io/file project-root))

            cmd (harness/build-cmd harness
                  {:cwd abs-root :model model :prompt tagged-prompt})

            _ (println (format "[planner] Running (%s:%s, max_pending: %d, current: %d)"
                               (name harness) (or model "default") max-pending pending-before))

            result (try
                     (process/sh cmd {:dir abs-root
                                      :in (harness/process-stdin harness tagged-prompt)
                                      :out :string :err :string})
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
