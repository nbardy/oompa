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
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.pprint :refer [print-table]]
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

(declare task-root-for-cwd)

(defn- log-ts
  "Readable wall-clock timestamp for worker log lines."
  []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
           (java.time.LocalDateTime/now)))

(defn- load-prompt
  "Load a prompt file. Tries path as-is first, then from package root."
  [path]
  (or (agent/load-custom-prompt path)
      (agent/load-custom-prompt (str package-root "/" path))))

(defn- snippet
  [s limit]
  (let [s (or s "")]
    (subs s 0 (min limit (count s)))))

(defn- build-template-tokens
  "Build token map for prompt template {var} substitution.
   Merges core/build-context (rich YAML header, queue, hotspots, etc.)
   with worker-level context (task_status, pending_tasks) and defaults
   for tokens that core/build-context doesn't produce (mode_hint, targets,
   recent_sec). Without these defaults, those {vars} leak into prompts."
  ([worker-context]
   (build-template-tokens worker-context nil))
  ([worker-context cwd]
   (let [pending (tasks/list-pending)
         core-ctx (core/build-context {:tasks pending
                                       :repo (System/getProperty "user.dir")})
         task-root (task-root-for-cwd (or cwd (System/getProperty "user.dir")))]
     (merge {:mode_hint "propose"
             :targets "*"
             :recent_sec "180"
             :TASK_ROOT task-root
             :TASKS_ROOT task-root}
            core-ctx
            worker-context))))

(defn- task-root-for-cwd
  "Return the relative tasks root for commands issued from cwd."
  [cwd]
  (let [cwd-file (io/file cwd)
        local-tasks (io/file cwd-file "tasks")
        parent-tasks (some-> cwd-file .getParentFile (io/file "tasks"))]
    (cond
      (and parent-tasks (.exists parent-tasks)) "../tasks"
      (.exists local-tasks) "tasks"
      :else "tasks")))

(defn- render-task-header
  "Inject runtime task path into auto-injected task header."
  [raw-header cwd]
  (let [task-root (task-root-for-cwd cwd)]
    (-> (or raw-header "")
        (str/replace "{{TASK_ROOT}}" task-root)
        (str/replace "{{TASKS_ROOT}}" task-root)
        (str/replace "{TASK_ROOT}" task-root)
        (str/replace "{TASKS_ROOT}" task-root))))

(def ^:private default-max-working-resumes 5)
(def ^:private default-max-needs-followups 1)
(def ^:private default-max-wait-for-tasks 600)

(defn create-worker
  "Create a worker config.
   :prompts is a string or vector of strings — paths to prompt files.
   :can-plan when false, worker waits for tasks before starting (backpressure).
   :reasoning reasoning effort level (e.g. \"low\", \"medium\", \"high\") — codex only.
   :review-prompts paths to reviewer prompt files (loaded and concatenated for review).
   :wait-between seconds to sleep between cycles (nil or 0 = no wait).
   :max-wait-for-tasks max seconds a non-planner waits for tasks before giving up (default 600).
   :max-working-resumes max consecutive working resumes before nudge+kill (default 5).
   :max-needs-followups max NEEDS_FOLLOWUP continuations in one cycle (default 1)."
  [{:keys [id swarm-id harness model runs max-cycles iterations prompts can-plan reasoning
           reviewers wait-between
           max-working-resumes max-needs-followups max-wait-for-tasks]}]
  (let [cycle-cap (or max-cycles iterations runs 10)
        run-goal (or runs iterations 10)]
  {:id id
   :swarm-id swarm-id
   :harness (or harness :codex)
   :model model
   ;; Legacy compatibility: :iterations remains the cycle cap.
   :iterations cycle-cap
   :max-cycles cycle-cap
   :runs run-goal
   :prompts (cond
              (vector? prompts) prompts
              (string? prompts) [prompts]
              :else [])
   :can-plan (if (some? can-plan) can-plan true)
   :reasoning reasoning
   :wait-between (when (and wait-between (pos? wait-between)) wait-between)
   :max-wait-for-tasks (let [v (or max-wait-for-tasks default-max-wait-for-tasks)]
                         (if (and (number? v) (pos? v))
                           v
                           default-max-wait-for-tasks))
   :reviewers reviewers
   :max-working-resumes (or max-working-resumes default-max-working-resumes)
   :max-needs-followups (or max-needs-followups default-max-needs-followups)
   :completed 0
   :status :idle}))

;; =============================================================================
;; Task Execution
;; =============================================================================

(def ^:private max-review-retries 3)

;; Nudge prompt injected when a worker hits max-working-resumes consecutive
;; "working" outcomes without signaling. Gives the agent one final chance to
;; produce something mergeable before the session is killed.
(def ^:private nudge-prompt
  (str "You have been working for a long time without signaling completion.\n"
       "You MUST take one of these actions NOW:\n\n"
       "1. If you have meaningful changes: commit them and signal COMPLETE_AND_READY_FOR_MERGE\n"
       "2. If scope is too large: create follow-up tasks in tasks/pending/ for remaining work,\n"
       "   commit what you have (even partial notes/design docs), and signal COMPLETE_AND_READY_FOR_MERGE\n"
       "3. If you truly cannot produce a merge-ready artifact this turn, signal NEEDS_FOLLOWUP\n"
       "   and explain the remaining work. The framework will keep your claimed tasks and give you\n"
       "   one targeted follow-up prompt. This is not success.\n\n"
       "Do NOT continue working without producing a signal."))

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
                      "No claims succeeded. CLAIM different tasks. If you cannot finish a mergeable artifact after trying hard, signal NEEDS_FOLLOWUP with a short explanation."))]
    {:claimed claimed-ids
     :failed failed-ids
     :resume-prompt prompt}))

(defn- active-claimed-task-ids
  "Union of tasks claimed earlier in the cycle and tasks moved into current/
   during the latest attempt."
  [claimed-ids mv-claimed-tasks]
  (-> (set claimed-ids)
      (into mv-claimed-tasks)))

(defn- recycle-task-id-set!
  "Recycle a set of claimed task IDs from current/ back to pending/.
   Returns a vector of recycled IDs."
  [worker-id task-ids]
  (let [task-ids (set (remove nil? task-ids))
        recycled (when (seq task-ids)
                   (tasks/recycle-tasks! task-ids))]
    (when (seq recycled)
      (println (format "[%s] Recycled %d claimed task(s): %s"
                       worker-id (count recycled) (str/join ", " recycled))))
    (vec (or recycled []))))

(defn- recycle-active-claims!
  "Recycle all claims active in the current cycle."
  [worker-id claimed-ids mv-claimed-tasks]
  (recycle-task-id-set! worker-id (active-claimed-task-ids claimed-ids mv-claimed-tasks)))

(defn- build-needs-followup-prompt
  "Prompt injected after NEEDS_FOLLOWUP so the worker keeps ownership and
   closes the loop in the same cycle."
  [claimed-ids output]
  (let [context (build-context)
        explanation (some-> output
                            (str/replace #"(?is)^\s*NEEDS_FOLLOWUP\b[\s:.-]*" "")
                            str/trim)]
    (str "## NEEDS_FOLLOWUP Follow-up\n\n"
         (if (seq claimed-ids)
           (str "You still own these claimed tasks: "
                (str/join ", " (sort claimed-ids))
                "\n\n")
           "You do not currently own any claimed tasks.\n\n")
         "Continue the SAME cycle and finish a merge-ready artifact.\n"
         "Do not output NEEDS_FOLLOWUP again unless you are still blocked after this follow-up.\n"
         "Prefer the smallest useful diff. If scope is too large, create concrete follow-up tasks in the pending queue and still ship the artifact you have.\n\n"
         (when (seq explanation)
           (str "Your previous explanation:\n"
                explanation
                "\n\n"))
         "Task Status: " (:task_status context) "\n"
         "Remaining Pending:\n"
         (if (str/blank? (:pending_tasks context))
           "(none)"
           (:pending_tasks context))
         "\n\nWhen ready, signal COMPLETE_AND_READY_FOR_MERGE.")))

(defn- run-agent!
  "Run agent with prompt, return {:output :done? :merge? :claim-ids :exit :session-id}.
   When resume? is true, continues the existing session with a lighter prompt.
   resume-prompt-override: when non-nil, replaces the default resume prompt
   (used to inject CLAIM results). All harness-specific CLI knowledge
   is delegated to harness/build-cmd."
  [{:keys [id swarm-id harness model prompts reasoning]} worktree-path context session-id resume?
   & {:keys [resume-prompt-override]}]
  (let [session-id (or session-id (harness/make-session-id harness))
        template-tokens (build-template-tokens context worktree-path)
        resume-prompt-override (when resume-prompt-override
                                 (-> resume-prompt-override
                                     (render-task-header worktree-path)
                                     (agent/tokenize template-tokens)))

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

        result (try
                 (harness/run-command! harness
                                       {:cwd abs-worktree :model model :reasoning reasoning
                                        :session-id session-id :resume? resume?
                                        :prompt tagged-prompt :format? true})
                 (catch Exception e
                   (println (format "[%s] Agent exception: %s" id (.getMessage e)))
                   {:exit -1 :out "" :err (.getMessage e)}))

        {:keys [output session-id warning raw-snippet]}
        (harness/parse-output harness (:out result) session-id)
        stderr-snippet (let [stderr (some-> (:err result) str/trim)]
                         (when (seq stderr)
                           (subs stderr 0 (min 400 (count stderr)))))]

    {:output output
     :exit (:exit result)
     :done? (agent/done-signal? output)
     :merge? (agent/merge-signal? output)
     :merge-complete-sha (agent/parse-merge-complete-signal output)
     :needs-followup? (agent/needs-followup-signal? output)
     :claim-ids (agent/parse-claim-signal output)
     :session-id session-id
     :parse-warning warning
     :raw-snippet raw-snippet
     :stderr-snippet stderr-snippet}))

(defn- run-reviewer!
  "Run reviewer on worktree changes.
   Uses custom review-prompts when configured, otherwise falls back to default.
   prev-feedback: vector of previous review outputs (for multi-round context).
   Returns {:verdict :approved|:needs-changes, :comments [...], :output string}"
  [{:keys [id swarm-id reviewers]} worktree-path prev-feedback]
  (let [start-ms (System/currentTimeMillis)
        ;; -U10 gives 10 lines of context (vs default 3) so reviewer sees more
        ;; surrounding code without needing to shell out and read files.
        ;; -W extends hunks to show the enclosing function for each change.
        ;; 24000 char limit gives ~500-600 lines of diff — enough for most PRs
        ;; to be reviewed without tool calls to read files.
        diff-result (process/sh ["git" "diff" "-U10" "-W" "main"]
                                {:dir worktree-path :out :string :err :string})
        diff-content (let [d (:out diff-result)]
                       (if (> (count d) 24000)
                         (str (subs d 0 24000) "\n... [diff truncated at 24000 chars]")
                         d))

        swarm-id* (or swarm-id "unknown")

        ;; Only include the most recent round's feedback — the worker has already
        ;; attempted fixes based on it, so the reviewer just needs to verify.
        history-block (when (seq prev-feedback)
                        (let [latest (last prev-feedback)
                              truncated (if (> (count latest) 2000)
                                          (str (subs latest 0 2000) "\n... [feedback truncated]")
                                          latest)]
                          (str "\n## Previous Review (Round " (count prev-feedback) ")\n\n"
                               "The worker has attempted fixes based on this feedback. "
                               "Verify the issues below are resolved. Do NOT raise new issues.\n\n"
                               truncated
                               "\n\n")))

        abs-wt (.getAbsolutePath (io/file worktree-path))

        ;; Try each reviewer until one succeeds and returns a verdict
        result (reduce (fn [_ {:keys [harness model prompts]}]
                         (let [custom-prompt (when (seq prompts)
                                               (->> prompts
                                                    (map load-prompt)
                                                    (remove nil?)
                                                    (str/join "\n\n")))
                               review-body (str (or custom-prompt
                                                     (str "Review the changes in this worktree.\n"
                                                          "Focus on architecture and design, not style.\n"))
                                                "\n\nDiff:\n```\n" diff-content "\n```\n"
                                                (when history-block history-block)
                                                "\nYour verdict MUST be on its own line, exactly one of:\n"
                                                "VERDICT: APPROVED\n"
                                                "VERDICT: NEEDS_CHANGES\n\n"
                                                "Pick APPROVED if the changes are correct and complete. "
                                                "Pick NEEDS_CHANGES if there are specific issues to fix.\n"
                                                "If you pick NEEDS_CHANGES, list every issue as a numbered item with "
                                                "the file path and what needs to change.\n")
                               review-prompt (str "[oompa:" swarm-id* ":" id "] " review-body)
                               res (try
                                        (harness/run-command! harness {:cwd abs-wt :model model :prompt review-prompt})
                                        (catch Exception e
                                          {:exit -1 :out "" :err (.getMessage e)}))
                               parsed (harness/parse-output harness (:out res) nil)
                               output (or (:output parsed) "")
                               has-verdict? (or (re-find #"VERDICT:\s*APPROVED" output)
                                                (re-find #"VERDICT:\s*NEEDS_CHANGES" output)
                                                (re-find #"VERDICT:\s*REJECTED" output)
                                                (re-find #"(?i)\bAPPROVED\b" output))]
                           (if (and (= (:exit res) 0) has-verdict?)
                             (reduced res)
                             (do
                               (println (format "[%s] Reviewer %s failed or returned no verdict, falling back..." id model))
                               res))))
                       {:exit -1 :out "" :err "No reviewers configured or no verdict returned"}
                       reviewers)

        output (:out result)

        ;; Parse verdict
        verdict (cond
                  (re-find #"VERDICT:\s*APPROVED" output) :approved
                  (re-find #"VERDICT:\s*NEEDS_CHANGES" output) :needs-changes
                  (re-find #"VERDICT:\s*REJECTED" output) :needs-changes
                  (re-find #"(?i)\bAPPROVED\b" output) :approved
                  :else :needs-changes)
        duration-ms (- (System/currentTimeMillis) start-ms)]

    (println (format "[%s] Reviewer verdict: %s" id (name verdict)))
    (let [summary (subs output 0 (min 300 (count output)))]
      (println (format "[%s] Review: %s%s" id summary
                       (if (> (count output) 300) "..." ""))))

    {:verdict verdict
     :comments (when (not= (:exit result) 0)
                 [(:err result)])
     :output output
     :duration-ms duration-ms}))

(defn- run-fix!
  "Ask worker to fix issues based on reviewer feedback.
   all-feedback: vector of all reviewer outputs so far (accumulated across rounds).
   session-id: when non-nil, resumes the worker's existing session so it retains
   full context of the work it already did — avoids re-reading all changed files.
   Returns {:output string, :exit int, :session-id string}"
  [{:keys [id swarm-id harness model]} worktree-path all-feedback session-id]
  (let [start-ms (System/currentTimeMillis)
        swarm-id* (or swarm-id "unknown")
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

        result (try
                 (harness/run-command! harness
                                       (cond-> {:cwd abs-wt :model model :prompt fix-prompt}
                                         ;; Resume existing session so the worker keeps
                                         ;; full context of the code it already wrote.
                                         session-id (assoc :session-id session-id
                                                           :resume? true)))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))
        parsed (harness/parse-output harness (:out result) session-id)
        duration-ms (- (System/currentTimeMillis) start-ms)]

    {:output (:output parsed)
     :exit (:exit result)
     :session-id (:session-id parsed)
     :duration-ms duration-ms}))

(defn- collect-divergence-context
  "Collect context about how a worktree branch has diverged from main.
   Returns a map with :branch-log, :main-log, :diff-stat strings."
  [wt-path]
  (let [git-out (fn [& args] (:out (process/sh (vec args) {:dir wt-path :out :string :err :string})))
        branch-log (git-out "git" "log" "--oneline" "main..HEAD")
        main-log   (git-out "git" "log" "--oneline" "HEAD..main")
        diff-stat  (git-out "git" "diff" "--stat" "main")]
    {:branch-log (or branch-log "(none)")
     :main-log   (or main-log "(none)")
     :diff-stat  (or diff-stat "(none)")}))

(defn- first-nonblank-line
  "Return first non-blank line from text for compact logging."
  [s]
  (some->> (or s "")
           str/split-lines
           (remove str/blank?)
           first))

(def ^:private max-resolve-attempts
  "Max resolver agent launches before giving up on sync or merge recovery."
  5)

(defn- abort-any-merge!
  "Ensure no merge is in progress. Tries --abort first, falls back to hard reset."
  [dir]
  (let [abort (process/sh ["git" "merge" "--abort"] {:dir dir :out :string :err :string})]
    (when-not (zero? (:exit abort))
      (process/sh ["git" "reset" "--hard" "HEAD"] {:dir dir}))))

(defn- try-merge-main!
  "Try `git merge main` in a worktree. Returns {:ok? bool :error string}.
   On failure, cleans up any merge state to leave the worktree clean."
  [wt-path]
  ;; Guard: clean up leftover merge state from a crashed previous attempt
  (when (.exists (io/file wt-path ".git"))
    ;; Worktrees use .git as a file pointing to the real gitdir; MERGE_HEAD
    ;; lives in the worktree's gitdir. Just attempt abort unconditionally —
    ;; it's a no-op if no merge is in progress.
    (process/sh ["git" "merge" "--abort"] {:dir wt-path}))
  (let [result (process/sh ["git" "merge" "main" "--no-edit"]
                           {:dir wt-path :out :string :err :string})]
    (if (zero? (:exit result))
      {:ok? true}
      (do
        (abort-any-merge! wt-path)
        {:ok? false
         :error (str (:out result) "\n" (:err result))}))))

(defn- sync-worktree-to-main!
  "Sync worktree branch with main before merge-to-main!.
   Fast path: git merge main succeeds cleanly → :synced.
   Conflict path: launch resolver agent (up to 5 attempts) with context,
   then verify by trying git merge main again after each attempt.
   Runs OUTSIDE the merge-lock so the agent doesn't block other workers.
   Returns :synced | :resolved | :failed."
  [worker wt-path worker-id]
  (let [first-try (try-merge-main! wt-path)]
    (if (:ok? first-try)
      (do (println (format "[%s] Worktree synced to main" worker-id))
          :synced)
      (loop [attempt 1
             last-error (:error first-try)]
        (println (format "[%s] Resolve attempt %d/%d" worker-id attempt max-resolve-attempts))
        (let [{:keys [branch-log main-log diff-stat]} (collect-divergence-context wt-path)
              resolve-prompt (str "[oompa:" (or (:swarm-id worker) "unknown") ":" worker-id "] "
                                  "Your branch cannot merge into main.\n\n"
                                  "Error:\n" last-error "\n\n"
                                  "Your commits (not on main):\n" branch-log "\n\n"
                                  "New commits on main:\n" main-log "\n\n"
                                  "Divergence:\n" diff-stat "\n\n"
                                  "Make your branch cleanly mergeable into main. "
                                  "Preserve YOUR changes. You have full git access.")
              abs-wt (.getAbsolutePath (io/file wt-path))
              _ (try
                  (harness/run-command! (:harness worker)
                                        {:cwd abs-wt :model (:model worker) :prompt resolve-prompt})
                  (catch Exception e
                    (println (format "[%s] Resolver agent error: %s" worker-id (.getMessage e)))))
              recheck (try-merge-main! wt-path)]
          (cond
            (:ok? recheck)
            (do (println (format "[%s] Resolved on attempt %d" worker-id attempt))
                :resolved)
            (>= attempt max-resolve-attempts)
            (do (println (format "[%s] Failed to resolve after %d attempts" worker-id max-resolve-attempts))
                :failed)
            :else (recur (inc attempt) (:error recheck))))))))

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
  [project-root swarm-id worker-id iteration]
  (let [swarm-token (or swarm-id (subs (str (java.util.UUID/randomUUID)) 0 8))
        work-id (format "s%s-%s-i%d" swarm-token worker-id iteration)
        wt-dir (format ".w%s" work-id)
        wt-branch (format "oompa/%s" work-id)
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

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- ms->seconds
  [ms]
  (/ ms 1000.0))

(defn- pct-of
  [part total]
  (if (pos? total)
    (* 100.0 (/ part (double total)))
    0.0))

(defn- init-cycle-timing
  []
  {:implementation-rounds-ms []
   :reviewer-response-ms []
   :review-fixes-ms []
   :optional-review-ms []
   :llm-calls []})

(defn- add-llm-call
  [timing section-name call-name duration-ms]
  (let [timing (or timing (init-cycle-timing))
        duration-ms (max 0 (long (or duration-ms 0)))]
    (-> timing
        (update section-name (fnil conj []) duration-ms)
        (update :llm-calls conj {:name call-name
                                 :section section-name
                                 :duration-ms duration-ms}))))

(defn- cycle-llm-total-ms
  [timing]
  (let [sections [:implementation-rounds-ms :reviewer-response-ms :review-fixes-ms :optional-review-ms]]
    (->> sections
         (map #(reduce + 0 (or (get timing %) [])))
         (reduce + 0))))

(defn- with-call-percent
  [timing total-ms]
  (update timing :llm-calls
          (fn [calls]
            (mapv (fn [{:keys [duration-ms] :as call}]
                    (assoc call :percent (pct-of duration-ms total-ms)))
                  calls))))

(defn- format-timing-segment
  [label durations total-ms]
  (let [durations (vec (or durations []))
        items (if (seq durations)
                (str/join ", "
                          (map #(format "%.2fs (%.1f%%)"
                                        (ms->seconds %) (pct-of % total-ms))
                               durations))
                "-")
        section-ms (reduce + 0 durations)]
    (format "%s=[%s] %.2fs (%.1f%%)"
            label
            items
            (ms->seconds section-ms)
            (pct-of section-ms total-ms))))

(defn- format-cycle-timing
  [{:keys [implementation-rounds-ms reviewer-response-ms review-fixes-ms optional-review-ms]}
   total-ms]
  (let [llm-ms (cycle-llm-total-ms {:implementation-rounds-ms implementation-rounds-ms
                                    :reviewer-response-ms reviewer-response-ms
                                    :review-fixes-ms review-fixes-ms
                                    :optional-review-ms optional-review-ms})
        harness-ms (max 0 (- total-ms llm-ms))]
    (str "timing: "
         (format-timing-segment "Implementation" implementation-rounds-ms total-ms)
         " | "
         (format-timing-segment "Reviewer" reviewer-response-ms total-ms)
         " | "
         (format-timing-segment "Fixes" review-fixes-ms total-ms)
         " | "
         (format-timing-segment "OptionalReview" optional-review-ms total-ms)
         " | LLM="
         (format "%.2fs (%.1f%%)" (ms->seconds llm-ms) (pct-of llm-ms total-ms))
         " | Harness="
         (format "%.2fs (%.1f%%)" (ms->seconds harness-ms) (pct-of harness-ms total-ms))
         " | Total="
         (format "%.2fs" (ms->seconds total-ms)))))

(defn- safe-number
  [v]
  (if (number? v) (long v) 0))

(defn- safe-sum
  [v]
  (reduce + 0 (or v [])))

(defn- format-ms
  [ms]
  (format "%.2fs" (ms->seconds (safe-number ms))))

(defn- cycle-time-sum
  [{:keys [implementation-rounds-ms reviewer-response-ms review-fixes-ms optional-review-ms] :as timing-ms}
   duration-ms]
  (let [impl (safe-sum implementation-rounds-ms)
        review (safe-sum reviewer-response-ms)
        fixes (safe-sum review-fixes-ms)
        optional (safe-sum optional-review-ms)
        total (safe-number duration-ms)
        llm (+ impl review fixes optional)
        harness (max 0 (- total llm))]
    {:implementation-ms impl
     :review-ms review
     :fixes-ms fixes
     :optional-review-ms optional
     :llm-ms llm
     :harness-ms harness
     :total-ms total}))

(def ^:private empty-cycle-total
  {:implementation-ms 0
   :review-ms 0
   :fixes-ms 0
   :optional-review-ms 0
   :llm-ms 0
   :harness-ms 0
   :total-ms 0})

(defn- aggregate-cycle-timings-by-worker
  [swarm-id]
  (reduce (fn [acc {:keys [worker-id timing-ms duration-ms]}]
            (update acc worker-id
                    (fn [current]
                      (merge-with + (or current empty-cycle-total)
                                  (cycle-time-sum timing-ms duration-ms)))))
          {}
          (or (when swarm-id (runs/list-cycles swarm-id)) [])))

(defn- worker-summary-row
  [{:keys [id status completed cycles-completed merges claims rejections errors recycled review-rounds-total] :as _worker}
   {:keys [implementation-ms review-ms fixes-ms harness-ms total-ms]}]
  {:Worker id
   :Runs (or completed cycles-completed 0)
   :Cycles (or cycles-completed 0)
   :Status (name status)
   :Merges (or merges 0)
   :Claims (or claims 0)
   :Rejects (or rejections 0)
   :Errors (or errors 0)
   :Recycled (or recycled 0)
   :ReviewRounds (or review-rounds-total 0)
   :ImplMs (format-ms implementation-ms)
   :ReviewMs (format-ms review-ms)
   :FixMs (format-ms fixes-ms)
   :HarnessMs (format-ms harness-ms)
   :TotalMs (format-ms total-ms)})

(defn- emit-cycle-log!
  "Write cycle event log. Called at every cycle attempt exit point.
   session-id links to the Claude CLI conversation transcript on disk.
   No mutable summary state — all state is derived from immutable cycle logs."
  [swarm-id worker-id cycle attempt run start-ms session-id
   {:keys [outcome claimed-task-ids recycled-tasks error-snippet review-rounds timing-ms
           worktree-path signals merge-sha]}]
  (let [duration-ms (- (now-ms) start-ms)
        timing-ms (or timing-ms (init-cycle-timing))
        harness-ms (max 0 (- duration-ms (cycle-llm-total-ms timing-ms)))
        timing-ms (with-call-percent (assoc timing-ms
                                           :harness-ms harness-ms
                                           :llm-calls (or (:llm-calls timing-ms) []))
                                    duration-ms)]
    (runs/write-cycle-log!
      swarm-id worker-id cycle
      (cond-> {:run run
               :attempt attempt
               :outcome outcome
               :duration-ms duration-ms
               :claimed-task-ids (vec (or claimed-task-ids []))
               :recycled-tasks (or recycled-tasks [])
               :error-snippet error-snippet
               :review-rounds (or review-rounds 0)
               :session-id session-id
               :timing-ms timing-ms}
        worktree-path (assoc :worktree-path worktree-path)
        (seq signals)  (assoc :signals (vec signals))
        merge-sha      (assoc :merge-sha merge-sha)))
    (let [terminal-outcomes #{:merged :merge-failed :rejected :sync-failed :no-changes
                              :executor-done :stuck :error :interrupted :needs-followup}]
      (if (and outcome (contains? terminal-outcomes outcome))
        (do
          (println (format "[%s] %s" worker-id (format-cycle-timing timing-ms duration-ms)))
          (when worktree-path
            (println (format "[%s] worktree: %s" worker-id worktree-path)))
          (when (seq signals)
            (println (format "[%s] signals: %s" worker-id (str/join " → " signals)))))
        (println (format "[%s] Cycle %d attempt %d continuing"
                         worker-id cycle attempt))))))



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
        (when (str/ends-with? (.getName f) ".json")
          (try
            (let [task (json/parse-string (slurp f) true)]
              (when-not (:completed-by task)
                (spit f (str (json/generate-string
                              (assoc task
                                     :completed-by worker-id
                                     :completed-at (str (java.time.Instant/now))
                                     :review-rounds (or review-rounds 0)
                                     :merged-commit (or commit-hash "unknown"))
                              {:pretty true})
                             "\n"))))
            (catch Exception e
              (println (format "[%s] Failed to annotate task %s: %s"
                               worker-id (.getName f) (.getMessage e))))))))))

(defn- merge-to-main!
  "Merge worktree changes to main branch. Serialized via merge-lock to prevent
   concurrent workers from corrupting the git index. On success, moves claimed
   tasks current→complete and annotates metadata. Returns
   {:ok? bool :reason keyword :message string}.
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
                       (zero? (:exit merge-result)))
          failure-text (str/join "\n"
                                 (remove str/blank?
                                         [(:out checkout-result)
                                          (:err checkout-result)
                                          (when merge-result (:out merge-result))
                                          (when merge-result (:err merge-result))]))]
      (if success
        (let [merge-sha (get-head-hash project-root)
              completed (when (seq claimed-task-ids)
                          (tasks/complete-by-ids! claimed-task-ids))
              completed-count (count (or completed []))]
          (println (format "[%s] Merged → %s" worker-id (or merge-sha "unknown")))
          (when (seq completed)
            (println (format "[%s] Completed %d task(s): %s"
                             worker-id completed-count (str/join ", " completed))))
          (annotate-completed-tasks! project-root worker-id review-rounds)
          {:ok? true
           :reason :merged
           :message (str "merged → " (or merge-sha "unknown"))
           :merge-sha merge-sha
           :completed-count completed-count})
        ;; FAILED: Clean up git state before releasing merge-lock.
        ;; A conflict leaves .git/MERGE_HEAD and poisons the shared index.
        (do
          (println (format "[%s] MERGE FAILED: %s"
                           worker-id
                           (or (first-nonblank-line failure-text) "no output")))
          (abort-any-merge! project-root)
          {:ok? false
           :reason :conflict
           :message (or (first-nonblank-line failure-text) "merge failed")})))))

(defn- recover-merge-failure!
  "On merge-to-main failure, re-sync branch with main and retry merge.
   Delegates conflict resolution to sync-worktree-to-main! (which has its
   own retry loop). Must run outside merge-lock."
  [worker wt-path wt-id worker-id project-root review-rounds claimed-task-ids _merge-result]
  (println (format "[%s] Merge failed, re-syncing with main..." worker-id))
  (let [sync-status (sync-worktree-to-main! worker wt-path worker-id)]
    (if (= :failed sync-status)
      {:ok? false :reason :conflict :message "could not resolve conflicts with main"}
      (do
        (println (format "[%s] Re-synced, retrying merge" worker-id))
        (merge-to-main! wt-path wt-id worker-id project-root review-rounds claimed-task-ids)))))

(def ^:private max-merge-agent-attempts 3)

(defn- build-merge-prompt
  "Prompt injected when resuming the original agent session to do the merge.
   Agent must run git itself, resolve any conflicts, and signal MERGE_COMPLETE(sha)."
  [wt-branch project-root]
  (str "## Merge Authorization\n\n"
       "Your work has been reviewed and approved. You are now authorized to merge to main.\n\n"
       "Steps:\n"
       "1. Commit any uncommitted changes in your worktree (git add -A && git commit -m 'wip')\n"
       "2. In the project root (" project-root "), run:\n"
       "   git checkout main && git merge " wt-branch " --no-edit\n"
       "3. If there are merge conflicts: resolve them, then git add -A && git commit --no-edit\n"
       "4. After the merge succeeds, get the commit SHA: git rev-parse --short HEAD\n"
       "5. Signal MERGE_COMPLETE(sha) — e.g. MERGE_COMPLETE(a3f7d2c)\n\n"
       "IMPORTANT: Only signal MERGE_COMPLETE after the merge is actually on main.\n"
       "If you cannot resolve conflicts after trying hard, signal NEEDS_FOLLOWUP with details."))

(defn- run-merge-agent!
  "Resume the original worker session and instruct it to merge its branch to main.
   Serialized via merge-lock so concurrent workers don't corrupt the git index.
   Returns {:ok? bool :sha string|nil :message string}."
  [worker wt-path wt-branch project-root session-id worker-id]
  (locking merge-lock
    (loop [attempt 1]
      (println (format "[%s] Merge agent attempt %d/%d" worker-id attempt max-merge-agent-attempts))
      (let [prompt (build-merge-prompt wt-branch project-root)
            abs-wt (.getAbsolutePath (io/file wt-path))
            result (try
                     (harness/run-command! (:harness worker)
                                           {:cwd abs-wt
                                            :model (:model worker)
                                            :reasoning (:reasoning worker)
                                            :session-id session-id
                                            :resume? true
                                            :prompt prompt})
                     (catch Exception e
                       (println (format "[%s] Merge agent error: %s" worker-id (.getMessage e)))
                       {:exit -1 :out "" :err (.getMessage e)}))
            {:keys [output]} (harness/parse-output (:harness worker) (:out result) session-id)
            sha (agent/parse-merge-complete-signal output)
            gave-up? (agent/needs-followup-signal? output)]
        (cond
          sha
          (do (println (format "[%s] Merged → %s" worker-id sha))
              {:ok? true :sha sha :message (str "merged → " sha)})

          gave-up?
          (do (println (format "[%s] Merge agent gave up" worker-id))
              {:ok? false :sha nil :message "agent signaled NEEDS_FOLLOWUP during merge"})

          (>= attempt max-merge-agent-attempts)
          (do (println (format "[%s] Merge agent did not signal MERGE_COMPLETE after %d attempts" worker-id max-merge-agent-attempts))
              {:ok? false :sha nil :message "merge agent exhausted attempts without MERGE_COMPLETE"})

          :else (recur (inc attempt)))))))

(defn- complete-merge!
  "After agent confirms merge, move tasks to complete and annotate them.
   Returns completed task count."
  [project-root worker-id review-rounds claimed-task-ids sha]
  (let [completed (when (seq claimed-task-ids)
                    (tasks/complete-by-ids! claimed-task-ids))
        completed-count (count (or completed []))]
    (when (seq completed)
      (println (format "[%s] Completed %d task(s): %s"
                       worker-id completed-count (str/join ", " completed))))
    (annotate-completed-tasks! project-root worker-id review-rounds)
    completed-count))

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
   session-id: the worker's proposer session — passed to run-fix! so it resumes
   the same conversation instead of starting from scratch.
   Returns {:approved? bool, :attempts int}"
  [worker wt-path worker-id iteration & [{:keys [cycle-timing session-id]}]]
  (if (empty? (:reviewers worker))
    ;; No reviewer configured, auto-approve
    {:approved? true :attempts 0 :timing (or cycle-timing (init-cycle-timing))}

    ;; Run review loop with accumulated feedback
    (loop [attempt 1
           prev-feedback []
           fix-session-id session-id
           timing (or cycle-timing (init-cycle-timing))]
      (println (format "[%s] Review attempt %d/%d" worker-id attempt max-review-retries))
      (let [{:keys [verdict output duration-ms]} (run-reviewer! worker wt-path prev-feedback)
            timing (add-llm-call timing
                                 :reviewer-response-ms
                                 (str "review_" attempt)
                                 (or duration-ms 0))
            diff-files (diff-file-names wt-path)]

        ;; Persist review log for this round
        (when (:swarm-id worker)
          (runs/write-review-log! (:swarm-id worker) worker-id iteration attempt
                                  {:verdict verdict
                                   :output output
                                   :duration-ms (or duration-ms 0)
                                   :diff-files (or diff-files [])}))

        (case verdict
          :approved
          (do
            (println (format "[%s] Reviewer APPROVED (attempt %d)" worker-id attempt))
            {:approved? true :attempts attempt :timing timing})

          ;; :needs-changes — always give the worker a chance to fix.
          ;; Hard rejection only happens when max review rounds are exhausted.
          (let [all-feedback (conj prev-feedback output)]
            (if (>= attempt max-review-retries)
              (do
                (println (format "[%s] Max review retries reached (%d rounds)" worker-id attempt))
                {:approved? false :attempts attempt :timing timing})
              (do
                (println (format "[%s] Reviewer requested changes, fixing..." worker-id))
                ;; Resume the worker's session so it keeps context of its own code
                (let [{:keys [duration-ms session-id]} (run-fix! worker wt-path all-feedback fix-session-id)
                      timing (add-llm-call timing
                                           :review-fixes-ms
                                           (str "fix_" attempt)
                                           (or duration-ms 0))]
                   (recur (inc attempt) all-feedback (or session-id fix-session-id) timing))))))))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

;; Workers can wait for tasks before giving up; default is 10 minutes.
;; This keeps workers alive while planners/designers ramp up the queue.
(def ^:private wait-poll-interval 10)
(def ^:private max-consecutive-errors 5)

(defn- backoff-sleep! [id errors]
  (when (< errors max-consecutive-errors)
    (let [wait-sec (* 60 (int (Math/pow 2 (dec errors))))]
      (println (format "[%s] Backing off for %d seconds before next retry (%d/%d)..." id wait-sec errors (dec max-consecutive-errors)))
      (Thread/sleep (* 1000 wait-sec)))))


(defn- wait-for-tasks!
  "Wait up to max-wait-seconds for pending/current tasks to appear.
   Used for backpressure on workers that can't create their own tasks (can_plan: false).
   Polls every 10 seconds, logs every 60 seconds."
  [worker-id max-wait-seconds]
  (loop [waited 0]
    (cond
      (pos? (tasks/pending-count)) true
      (pos? (tasks/current-count)) true
      (>= waited max-wait-seconds)
      (do (println (format "[%s] [%s] No tasks after %ds, giving up"
                           worker-id (log-ts) waited))
          false)
      :else
      (do (when (zero? (mod waited 60))
            (println (format "[%s] [%s] Waiting for tasks... (%ds/%ds)"
                             worker-id (log-ts) waited max-wait-seconds)))
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

   A run is a terminal outcome (merged/rejected/error-like).
   A cycle is one worker turn/resume. Multiple cycles may occur in one run.
   Cycle cap is controlled by :max-cycles (legacy key: :iterations)."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id runs max-cycles iterations swarm-id wait-between
                max-wait-for-tasks max-needs-followups]} worker
        cycle-cap (or max-cycles iterations 10)
        run-goal (or runs iterations 10)
        project-root (System/getProperty "user.dir")]
    (println (format "[%s] Starting worker (%s:%s%s, goal=%d runs, cap=%d cycles%s)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     (if (:reasoning worker) (str ":" (:reasoning worker)) "")
                     run-goal
                     cycle-cap
                     (if wait-between (format ", %ds between" wait-between) "")))

    (when (and (not (:can-plan worker))
               (not (pos? (tasks/pending-count)))
               (not (pos? (tasks/current-count))))
      (wait-for-tasks! id max-wait-for-tasks))

    (loop [cycle 1
           attempt 1
           completed-runs 0
           consec-errors 0
           metrics {:merges 0 :rejections 0 :errors 0 :recycled 0 :review-rounds-total 0 :claims 0}
           session-id nil
           wt-state nil
           claimed-ids #{}
           claim-resume-prompt nil
           working-resumes 0
           needs-followups 0
           signals []]
      (let [finish (fn [status]
                     (assoc worker :completed completed-runs
                                   :runs-completed completed-runs
                                   :cycles-completed (dec cycle)
                                   :status status
                                   :merges (:merges metrics)
                                   :rejections (:rejections metrics)
                                   :errors (:errors metrics)
                                   :recycled (:recycled metrics)
                                   :review-rounds-total (:review-rounds-total metrics)
                                   :claims (:claims metrics)))
            current-run (inc completed-runs)]
        (cond
          (> cycle cycle-cap)
          (do
            (when wt-state
              (when (seq claimed-ids)
                (recycle-task-id-set! id claimed-ids))
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (println (format "[%s] Completed %d/%d runs in %d cycles (%d merges, %d claims, %d rejections, %d errors, %d recycled)"
                             id completed-runs run-goal (dec cycle)
                             (:merges metrics) (:claims metrics) (:rejections metrics) (:errors metrics) (:recycled metrics)))
            (finish :exhausted))

          (>= completed-runs run-goal)
          (do
            (when wt-state
              (when (seq claimed-ids)
                (recycle-task-id-set! id claimed-ids))
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (println (format "[%s] Reached run goal: %d/%d runs in %d cycles"
                             id completed-runs run-goal (dec cycle)))
            (finish :completed))

          @shutdown-requested?
          (do
            (println (format "[%s] Shutdown requested, stopping after %d cycles" id (dec cycle)))
            (when wt-state
              (when (seq claimed-ids)
                (let [recycled (tasks/recycle-tasks! claimed-ids)]
                  (when (seq recycled)
                    (println (format "[%s] Recycled %d claimed task(s) on shutdown" id (count recycled))))))
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (emit-cycle-log! swarm-id id cycle attempt current-run (now-ms) session-id
                             {:timing-ms (init-cycle-timing)
                              :outcome :interrupted})
            (finish :interrupted))

          :else
          (do
            (maybe-sleep-between! id wait-between cycle)

            (when (and (not (:can-plan worker))
                       (not (pos? (tasks/pending-count)))
                       (not (pos? (tasks/current-count))))
              (println (format "[%s] Queue empty, waiting for tasks before cycle %d" id cycle))
              (wait-for-tasks! id max-wait-for-tasks))

                (let [wt-state (try
                             (or wt-state (create-iteration-worktree! project-root swarm-id id cycle))
                             (catch Exception e
                               (println (format "[%s] Worktree creation failed: %s" id (.getMessage e)))
                               nil))]
              (if (nil? wt-state)
                (let [errors (inc consec-errors)
                      metrics (update metrics :errors inc)]
                  (if (>= errors max-consecutive-errors)
                    (do
                      (println (format "[%s] %d consecutive errors, stopping" id errors))
                      (finish :error))
                    (do (backoff-sleep! id errors)
                        (recur (inc cycle) 1 completed-runs errors metrics nil nil #{} nil 0 0 []))))

                (let [resume? (or (some? session-id) (some? claim-resume-prompt))
                      cycle-start-ms (now-ms)
                      cycle-timing (init-cycle-timing)
                      pre-current-ids (tasks/current-task-ids)
                      _ (println (format "[%s] %s cycle %d/%d (run %d/%d, attempt %d)"
                                         id
                                         (if (= attempt 1) "Starting" "Resuming")
                                         cycle cycle-cap current-run run-goal attempt))
                      context (build-context)
                      agent-start-ms (now-ms)
                      {:keys [output exit done? merge? needs-followup? claim-ids parse-warning raw-snippet] :as agent-result}
                      (run-agent! worker (:path wt-state) context session-id resume?
                                  :resume-prompt-override claim-resume-prompt)
                      cycle-timing (add-llm-call cycle-timing
                                                 :implementation-rounds-ms
                                                 "implementation"
                                                 (- (now-ms) agent-start-ms))
                      new-session-id (:session-id agent-result)
                      stderr-snippet (:stderr-snippet agent-result)
                      mv-claimed-tasks (detect-claimed-tasks pre-current-ids)
                      active-claimed-ids (active-claimed-task-ids claimed-ids mv-claimed-tasks)
                      wt-path (:path wt-state)
                      ;; Classify the signal for this attempt
                      signal-label (cond
                                     (not (zero? exit)) (str "error:exit-" exit)
                                     (and (seq claim-ids) (not merge?) (not done?))
                                     (str "claim:" (str/join "," claim-ids))
                                     merge? "merge"
                                     done? "done"
                                     needs-followup? "needs-followup"
                                     :else "working")
                      signals (conj signals signal-label)
                      emit! (fn [opts]
                              (emit-cycle-log! swarm-id id cycle attempt current-run cycle-start-ms new-session-id
                                               (merge {:worktree-path wt-path :signals signals} opts)))]
                  (cond
                    (not (zero? exit))
                    (let [errors (inc consec-errors)
                          recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                          metrics (-> metrics (update :errors inc) (update :recycled + (count recycled)))
                          error-msg (subs (or output "") 0 (min 200 (count (or output ""))))]
                      (println (format "[%s] Agent error (exit %d): %s" id exit error-msg))
                      (when (seq stderr-snippet)
                        (println (format "[%s] Agent stderr snippet: %s"
                                         id
                                         (snippet (str/replace stderr-snippet #"\s+" " ") 240))))
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :error
                                        :claimed-task-ids (vec active-claimed-ids)
                                        :recycled-tasks (seq recycled)
                                        :error-snippet error-msg})
                      (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                      (if (>= errors max-consecutive-errors)
                        (do
                          (println (format "[%s] %d consecutive errors, stopping" id errors))
                          (finish :error))
                        (do (backoff-sleep! id errors)
                            (recur (inc cycle) 1 (inc completed-runs) errors metrics nil nil #{} nil 0 0 []))))

                    (and (seq claim-ids) (not merge?) (not done?))
                    (let [_ (println (format "[%s] CLAIM signal: %s" id (str/join ", " claim-ids)))
                          {:keys [claimed resume-prompt]} (execute-claims! claim-ids)
                          new-claimed-ids (into active-claimed-ids claimed)
                          metrics (update metrics :claims + (count claimed))]
                      (println (format "[%s] Claimed %d/%d tasks" id (count claimed) (count claim-ids)))
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :claimed :claimed-task-ids (vec claimed)})
                      (recur cycle (inc attempt) completed-runs 0 metrics new-session-id wt-state
                             new-claimed-ids resume-prompt 0 0 signals))

                    merge?
                    (if (worktree-has-changes? (:path wt-state))
                      (if (task-only-diff? (:path wt-state))
                        (let [all-claimed active-claimed-ids]
                          (println (format "[%s] Task-only diff, auto-merging via agent" id))
                          (let [merge-result (run-merge-agent! worker (:path wt-state) (:branch wt-state) project-root new-session-id id)
                                merged? (:ok? merge-result)
                                sha (:sha merge-result)
                                _ (when merged? (complete-merge! project-root id 0 all-claimed sha))
                                recycled (when-not merged? (recycle-task-id-set! id all-claimed))
                                metrics (cond-> metrics
                                          merged? (update :merges inc)
                                          (seq recycled) (update :recycled + (count recycled)))]
                            (println (format "[%s] Cycle %d/%d complete" id cycle cycle-cap))
                            (emit!
                                             {:timing-ms cycle-timing
                                              :outcome (if merged? :merged :merge-failed)
                                              :merge-sha sha
                                              :claimed-task-ids (vec all-claimed)
                                              :recycled-tasks (seq recycled)
                                              :review-rounds 0})
                            (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                            (recur (inc cycle) 1 (inc completed-runs) 0 metrics nil nil #{} nil 0 0 [])))
                              (let [{:keys [approved? attempts timing]} (review-loop! worker (:path wt-state) id cycle {:cycle-timing cycle-timing :session-id new-session-id})
                                    cycle-timing (or timing cycle-timing)
                                    metrics (-> metrics
                                              (update :review-rounds-total + (or attempts 0))
                                              (cond-> (not approved?) (update :rejections inc)))]
                          (if approved?
                            (let [all-claimed active-claimed-ids
                                  merge-result (run-merge-agent! worker (:path wt-state) (:branch wt-state) project-root new-session-id id)
                                  merged? (:ok? merge-result)
                                  sha (:sha merge-result)
                                  _ (when merged? (complete-merge! project-root id (or attempts 0) all-claimed sha))
                                  recycled (when-not merged? (recycle-task-id-set! id all-claimed))
                                  metrics (cond-> metrics
                                            merged? (update :merges inc)
                                            (seq recycled) (update :recycled + (count recycled)))]
                              (println (format "[%s] Cycle %d/%d complete" id cycle cycle-cap))
                              (emit!
                                               {:timing-ms cycle-timing
                                                :outcome (if merged? :merged :merge-failed)
                                                :merge-sha sha
                                                :claimed-task-ids (vec all-claimed)
                                                :recycled-tasks (seq recycled)
                                                :review-rounds (or attempts 0)})
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (recur (inc cycle) 1 (inc completed-runs) 0 metrics nil nil #{} nil 0 0 []))
                            (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                                  metrics (update metrics :recycled + (count recycled))]
                              (println (format "[%s] Cycle %d/%d rejected" id cycle cycle-cap))
                              (emit!
                                               {:timing-ms cycle-timing
                                                :outcome :rejected
                                                :claimed-task-ids (vec active-claimed-ids)
                                                :recycled-tasks (seq recycled)
                                                :review-rounds (or attempts 0)})
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (recur (inc cycle) 1 (inc completed-runs) 0 metrics nil nil #{} nil 0 0 [])))))
                      (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                            metrics (update metrics :recycled + (count recycled))]
                        (println (format "[%s] Merge signaled but no changes, skipping" id))
                        (emit!
                                         {:timing-ms cycle-timing
                                          :outcome :no-changes
                                          :claimed-task-ids (vec active-claimed-ids)
                                          :recycled-tasks (seq recycled)})
                        (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                        (recur (inc cycle) 1 (inc completed-runs) 0 metrics nil nil #{} nil 0 0 [])))

                    done?
                    (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                          metrics (-> metrics
                                      (update :recycled + (count recycled))
                                      (update :errors inc))]
                      (println (format "[%s] Invalid __DONE__ signal from executor; stopping worker (cycle %d/%d)" id cycle cycle-cap))
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :error
                                        :claimed-task-ids (vec active-claimed-ids)
                                        :recycled-tasks (seq recycled)
                                        :error-snippet "__DONE__ is not a valid executor signal; use CLAIM(...) or COMPLETE_AND_READY_FOR_MERGE"})
                      (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                      (finish :error))

                    needs-followup?
                    (let [summary (subs (or output "") 0 (min 240 (count (or output ""))))
                          next-followups (inc needs-followups)]
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :needs-followup
                                        :claimed-task-ids (vec active-claimed-ids)
                                        :error-snippet summary})
                      (if (> next-followups max-needs-followups)
                        (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                              metrics (-> metrics
                                          (update :recycled + (count recycled))
                                          (update :errors inc))]
                          (println (format "[%s] NEEDS_FOLLOWUP exhausted (%d/%d); stopping worker" id next-followups max-needs-followups))
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (finish :error))
                        (let [followup-prompt (build-needs-followup-prompt active-claimed-ids output)]
                          (println (format "[%s] NEEDS_FOLLOWUP signal; continuing cycle with follow-up prompt (%d/%d)"
                                           id next-followups max-needs-followups))
                          (recur cycle (inc attempt) completed-runs 0 metrics new-session-id wt-state
                                 active-claimed-ids followup-prompt 0 next-followups signals))))

                    :else
                    (let [wr (inc working-resumes)
                          max-wr (:max-working-resumes worker)]
                      (when parse-warning
                        (if (str/includes? parse-warning "AUTH_REQUIRED:")
                          (println (format "[%s] LOGIN ISSUE: %s"
                                           id
                                           (str/replace parse-warning #"^AUTH_REQUIRED:\s*" "")))
                          (println (format "[%s] WARNING: %s" id parse-warning))))
                      (when (and parse-warning (seq raw-snippet))
                        (println (format "[%s] Raw output snippet: %s"
                                         id
                                         (snippet (str/replace raw-snippet #"\s+" " ") 240))))
                      (when (seq stderr-snippet)
                        (println (format "[%s] Agent stderr snippet: %s"
                                         id
                                         (snippet (str/replace stderr-snippet #"\s+" " ") 240))))
                      (cond
                        (> wr max-wr)
                        (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                              metrics (update metrics :recycled + (count recycled))]
                          (println (format "[%s] Stuck after %d working resumes + nudge, resetting session" id wr))
                          (emit!
                                           {:timing-ms cycle-timing
                                            :outcome :stuck
                                            :claimed-task-ids (vec active-claimed-ids)
                                            :recycled-tasks (seq recycled)})
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (recur (inc cycle) 1 (inc completed-runs) 0 metrics nil nil #{} nil 0 0 []))

                        (= wr max-wr)
                        (do
                          (println (format "[%s] Working... %d/%d resumes, nudging agent to wrap up" id wr max-wr))
                          (emit!
                                           {:timing-ms cycle-timing
                                            :outcome :working
                                            :claimed-task-ids (vec active-claimed-ids)})
                          (recur cycle (inc attempt) completed-runs 0 metrics new-session-id wt-state
                                 active-claimed-ids nudge-prompt wr needs-followups signals))

                        :else
                        (do
                          (println (format "[%s] Working... (will resume, %d/%d)" id wr max-wr))
                          (emit!
                                           {:timing-ms cycle-timing
                                            :outcome :working
                                            :claimed-task-ids (vec active-claimed-ids)})
                          (recur cycle (inc attempt) completed-runs 0 metrics new-session-id wt-state
                                 active-claimed-ids nil wr needs-followups signals))))))))))))))

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
  (let [swarm-id (-> workers first :swarm-id)
        stale-current (tasks/list-current)]
    (when (seq stale-current)
      (println (format "WARNING: %d task(s) already in current/ from a previous run. These may be stale claims."
                       (count stale-current)))
      (doseq [t stale-current]
        (println (format "  - %s: %s" (:id t) (:summary t))))
      (println "  Run `oompa requeue` to move them back to pending/ if they are stale."))
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
                            (future
                              (try
                                (run-worker! worker)
                                (catch Exception e
                                  (println (format "[%s] FATAL: %s" (:id worker) (.getMessage e)))
                                  (.printStackTrace e)
                                  (throw e))))))
                        workers))]

        (println "All workers launched. Waiting for completion...")
        (let [results (mapv (fn [f]
                              (try
                                (deref f)
                                (catch Exception e
                                  (println (format "Worker future failed: %s" (.getMessage e)))
                                  {:status :fatal-error :error (.getMessage e)})))
                            futures)]
          ;; Clean exit — tell shutdown hook not to write stopped.json
          (reset! shutdown-requested? false)
          ;; Remove the hook so it doesn't accumulate across calls
          (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch Exception _))
          (println "\nAll workers complete.")
          (let [timing-by-worker (aggregate-cycle-timings-by-worker swarm-id)
                rows (mapv (fn [result]
                             (let [row-id (or (:id result) "")
                                   totals (get timing-by-worker row-id empty-cycle-total)]
                               (worker-summary-row result totals)))
                            results)]
            (println "\nWorker Summary")
            (print-table [:Worker :Runs :Cycles :Status :Merges :Claims :Rejects :Errors :Recycled
                          :ReviewRounds :ImplMs :ReviewMs :FixMs :HarnessMs :TotalMs]
                         rows))

          ;; Write stopped event — all state derivable from cycle logs
          (when swarm-id
            (runs/write-stopped! swarm-id :completed)
            (println (format "\nStopped event written to runs/%s/stopped.json" swarm-id)))

          results)))))

;; =============================================================================
;; Planner — first-class config concept, NOT a worker
;; =============================================================================
;; The planner creates task JSON files in tasks/pending/.
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
                             "Create tasks in tasks/pending/ as .json files.\n"
                             "Maximum " (- max-pending pending-before) " new tasks.\n"
                             "Signal __DONE__ when finished planning.")
            swarm-id* (or swarm-id "unknown")
            tagged-prompt (str "[oompa:" swarm-id* ":planner] " prompt-text)
            abs-root (.getAbsolutePath (io/file project-root))

            _ (println (format "[planner] Running (%s:%s, max_pending: %d, current: %d)"
                               (name harness) (or model "default") max-pending pending-before))

            result (try
                     (harness/run-command! harness
                                           {:cwd abs-root :model model :prompt tagged-prompt})
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
