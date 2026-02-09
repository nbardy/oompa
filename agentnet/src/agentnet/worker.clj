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
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; codex-persist integration
;; =============================================================================

(def ^:private persist-cmd* (atom nil))
(def ^:private persist-missing-warned?* (atom false))

(defn- command-ok?
  "Return true if command vector is executable (exit code ignored)."
  [cmd]
  (try
    (do
      (process/sh (vec cmd) {:out :string :err :string :continue true})
      true)
    (catch Exception _
      false)))

(defn- resolve-codex-persist-cmd
  "Resolve codex-persist command vector.
   Order:
   1) CODEX_PERSIST_BIN env var
   2) codex-persist on PATH
   3) node ~/git/codex-persist/dist/cli.js"
  []
  (let [cached @persist-cmd*]
    (if (some? cached)
      cached
      (let [env-bin (System/getenv "CODEX_PERSIST_BIN")
            env-cmd (when (and env-bin (not (str/blank? env-bin)))
                      [env-bin])
            path-cmd ["codex-persist"]
            local-cli (str (System/getProperty "user.home") "/git/codex-persist/dist/cli.js")
            local-cmd (when (.exists (io/file local-cli))
                        ["node" local-cli])
            cmd (cond
                  (and env-cmd (command-ok? env-cmd)) env-cmd
                  (command-ok? path-cmd) path-cmd
                  (and local-cmd (command-ok? local-cmd)) local-cmd
                  :else false)]
        (reset! persist-cmd* cmd)
        cmd))))

(defn- safe-assistant-content
  "Pick a non-empty assistant message payload for persistence."
  [result]
  (let [out (or (:out result) "")
        err (or (:err result) "")
        exit-code (or (:exit result) -1)]
    (cond
      (not (str/blank? out)) out
      (not (str/blank? err)) (str "[agent stderr] " err)
      :else (str "[agent exit " exit-code "]"))))

(defn- persist-message!
  "Write a single message to codex-persist; no-op if unavailable."
  [worker-id session-id cwd role content]
  (let [resolved (resolve-codex-persist-cmd)]
    (if (and resolved (not= resolved false))
      (let [persist-cmd resolved
            payload (if (str/blank? content) "(empty)" content)
            result (try
                     (process/sh (into persist-cmd ["write" session-id cwd role payload])
                                 {:out :string :err :string})
                     (catch Exception e
                       {:exit -1 :out "" :err (.getMessage e)}))]
        (when-not (zero? (:exit result))
          (println (format "[%s] codex-persist write failed (%s)" worker-id role))))
      (when (compare-and-set! persist-missing-warned?* false true)
        (println "[oompa] codex-persist not found; set CODEX_PERSIST_BIN or install/link codex-persist")))))

;; =============================================================================
;; Worker State
;; =============================================================================

(def ^:private package-root
  "Root of the oompa package — set by bin/oompa.js, falls back to cwd."
  (or (System/getenv "OOMPA_PACKAGE_ROOT") "."))

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

(defn- run-agent!
  "Run agent with prompt, return {:output string, :done? bool, :merge? bool, :exit int, :session-id string}.
   When resume? is true and harness is :claude, uses --resume to continue the existing session
   with a lighter prompt (just task status + continue instruction)."
  [{:keys [id swarm-id harness model prompts reasoning]} worktree-path context session-id resume?]
  (let [;; Use provided session-id or generate fresh one
        session-id (or session-id (str/lower-case (str (java.util.UUID/randomUUID))))

        ;; Build prompt — lighter for resume (agent already has full context)
        prompt (if resume?
                 (str "Task Status: " (:task_status context) "\n"
                      "Pending: " (:pending_tasks context) "\n\n"
                      "Continue working. Signal COMPLETE_AND_READY_FOR_MERGE when your current task is done and ready for review.")
                 (let [task-header (or (load-prompt "config/prompts/_task_header.md") "")
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

        ;; Build command — both harnesses run with cwd=worktree, no sandbox
        ;; so agents can `..` to reach project root for task management
        ;; Claude: --resume flag continues existing session-id conversation
        ;; Codex: no resume support, always fresh (but worktree state persists)
        cmd (case harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-worktree]
                       model (into ["--model" model])
                       reasoning (into ["-c" (str "model_reasoning_effort=\"" reasoning "\"")])
                       true (conj "--" tagged-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"
                               "--session-id" session-id]
                        resume? (conj "--resume")
                        model (into ["--model" model])))

        _ (when (= harness :codex)
            (persist-message! id session-id abs-worktree "user" tagged-prompt))

        ;; Run agent — both run with cwd=worktree
        result (try
                 (if (= harness :claude)
                   (process/sh cmd {:dir abs-worktree :in tagged-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-worktree :out :string :err :string}))
                 (catch Exception e
                   (println (format "[%s] Agent exception: %s" id (.getMessage e)))
                   {:exit -1 :out "" :err (.getMessage e)}))]

    (when (= harness :codex)
      (persist-message! id session-id abs-worktree "assistant" (safe-assistant-content result)))

    {:output (:out result)
     :exit (:exit result)
     :done? (agent/done-signal? (:out result))
     :merge? (agent/merge-signal? (:out result))
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

        ;; Build command — cwd=worktree, no sandbox
        cmd (case review-harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       review-model (into ["--model" review-model])
                       true (conj "--" review-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"]
                        review-model (into ["--model" review-model])))

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

        cmd (case harness
              :codex (cond-> [(resolve-binary! "codex") "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       model (into ["--model" model])
                       true (conj "--" fix-prompt))
              :claude (cond-> [(resolve-binary! "claude") "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model])))

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

(defn- merge-to-main!
  "Merge worktree changes to main branch. Returns true on success, throws on failure."
  [wt-path wt-id worker-id project-root]
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
      (println (format "[%s] Merge successful" worker-id))
      (when merge-result
        (println (format "[%s] MERGE FAILED: %s" worker-id (:err merge-result)))))
    success))

(defn- review-loop!
  "Run review loop: reviewer checks → if issues, fix & retry → back to reviewer.
   Accumulates feedback across rounds so reviewer doesn't raise new issues
   and fixer has full context of all prior feedback.
   Returns {:approved? bool, :attempts int}"
  [worker wt-path worker-id]
  (if-not (and (:review-harness worker) (:review-model worker))
    ;; No reviewer configured, auto-approve
    {:approved? true :attempts 0}

    ;; Run review loop with accumulated feedback
    (loop [attempt 1
           prev-feedback []]
      (println (format "[%s] Review attempt %d/%d" worker-id attempt max-review-retries))
      (let [{:keys [verdict output]} (run-reviewer! worker wt-path prev-feedback)]
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

   Returns final worker state."
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

    (loop [iter 1
           completed 0
           consec-errors 0
           session-id nil    ;; persistent session-id (nil = start fresh)
           wt-state nil]     ;; {:dir :branch :path} or nil
      (if (> iter iterations)
        (do
          ;; Cleanup any lingering worktree
          (when wt-state
            (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
          (println (format "[%s] Completed %d iterations" id completed))
          (assoc worker :completed completed :status :exhausted))

        ;; Ensure worktree exists (create fresh if nil, reuse if persisted)
        (let [wt-state (try
                         (or wt-state (create-iteration-worktree! project-root id iter))
                         (catch Exception e
                           (println (format "[%s] Worktree creation failed: %s" id (.getMessage e)))
                           nil))]
          (if (nil? wt-state)
            ;; Worktree creation failed — count as error
            (let [errors (inc consec-errors)]
              (if (>= errors max-consecutive-errors)
                (do
                  (println (format "[%s] %d consecutive errors, stopping" id errors))
                  (assoc worker :completed completed :status :error))
                (recur (inc iter) completed errors nil nil)))

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
                (let [errors (inc consec-errors)]
                  (println (format "[%s] Agent error (exit %d): %s"
                                   id exit (subs (or output "") 0 (min 200 (count (or output ""))))))
                  (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                  (if (>= errors max-consecutive-errors)
                    (do
                      (println (format "[%s] %d consecutive errors, stopping" id errors))
                      (assoc worker :completed completed :status :error))
                    (recur (inc iter) completed errors nil nil)))

                ;; COMPLETE_AND_READY_FOR_MERGE — review, merge, reset session
                merge?
                (if (worktree-has-changes? (:path wt-state))
                  (let [{:keys [approved?]} (review-loop! worker (:path wt-state) id)]
                    (if approved?
                      (do
                        (merge-to-main! (:path wt-state) (:branch wt-state) id project-root)
                        (println (format "[%s] Iteration %d/%d complete" id iter iterations))
                        (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                        ;; If also __DONE__, stop after merge
                        (if (and done? (:can-plan worker))
                          (do
                            (println (format "[%s] Worker done after merge" id))
                            (assoc worker :completed (inc completed) :status :done))
                          (recur (inc iter) (inc completed) 0 nil nil)))
                      (do
                        (println (format "[%s] Iteration %d/%d rejected" id iter iterations))
                        (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                        (recur (inc iter) completed 0 nil nil))))
                  (do
                    (println (format "[%s] Merge signaled but no changes, skipping" id))
                    (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                    (recur (inc iter) completed 0 nil nil)))

                ;; __DONE__ without merge — only honor for planners
                (and done? (:can-plan worker))
                (do
                  (println (format "[%s] Received __DONE__ signal" id))
                  (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                  (println (format "[%s] Worker done after %d/%d iterations" id iter iterations))
                  (assoc worker :completed completed :status :done))

                ;; __DONE__ from executor — ignore, keep working
                (and done? (not (:can-plan worker)))
                (do
                  (println (format "[%s] Ignoring __DONE__ (executor)" id))
                  (recur (inc iter) completed consec-errors new-session-id wt-state))

                ;; No signal — agent still working, resume next iteration
                :else
                (do
                  (println (format "[%s] Working... (will resume)" id))
                  (recur (inc iter) completed 0 new-session-id wt-state))))))))))

;; =============================================================================
;; Multi-Worker Execution
;; =============================================================================

(defn run-workers!
  "Run multiple workers in parallel.

   Arguments:
     workers - seq of worker configs

   Returns seq of final worker states."
  [workers]
  (tasks/ensure-dirs!)
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
        (println (format "  [%s] %s - %d iterations"
                         (:id w)
                         (name (:status w))
                         (:completed w))))
      results)))
