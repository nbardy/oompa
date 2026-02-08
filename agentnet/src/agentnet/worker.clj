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

(defn- load-prompt
  "Load a prompt file. Tries path as-is first, then from package root."
  [path]
  (or (agent/load-custom-prompt path)
      (agent/load-custom-prompt (str package-root "/" path))))

(defn create-worker
  "Create a worker config.
   :prompts is a string or vector of strings — paths to prompt files.
   :can-plan when false, worker waits for tasks before starting (backpressure).
   :reasoning reasoning effort level (e.g. \"low\", \"medium\", \"high\") — codex only."
  [{:keys [id swarm-id harness model iterations prompts can-plan reasoning review-harness review-model]}]
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
  "Run agent with prompt, return {:output string, :done? bool, :exit int}"
  [{:keys [id swarm-id harness model prompts reasoning]} worktree-path context]
  (let [;; 1. Task header (always, from package)
        task-header (or (load-prompt "config/prompts/_task_header.md") "")

        ;; 2. Load and concatenate all user prompts (string or list)
        user-prompts (if (seq prompts)
                       (->> prompts
                            (map load-prompt)
                            (remove nil?)
                            (str/join "\n\n"))
                       (or (load-prompt "config/prompts/worker.md")
                           "You are a worker. Claim tasks, execute them, complete them."))

        ;; Assemble: task header + status + user prompts
        full-prompt (str task-header "\n"
                         "Task Status: " (:task_status context) "\n"
                         "Pending: " (:pending_tasks context) "\n\n"
                         user-prompts)
        session-id (str/lower-case (str (java.util.UUID/randomUUID)))
        swarm-id* (or swarm-id "unknown")
        tagged-prompt (str "[oompa:" swarm-id* ":" id "] " full-prompt)
        abs-worktree (.getAbsolutePath (io/file worktree-path))

        ;; Build command — both harnesses run with cwd=worktree, no sandbox
        ;; so agents can `..` to reach project root for task management
        cmd (case harness
              :codex (cond-> ["codex" "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-worktree]
                       model (into ["--model" model])
                       reasoning (into ["-c" (str "reasoning_effort=\"" reasoning "\"")])
                       true (conj "--" full-prompt))
              :claude (cond-> ["claude" "-p" "--dangerously-skip-permissions"
                               "--session-id" session-id]
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
     :done? (agent/done-signal? (:out result))}))

(defn- run-reviewer!
  "Run reviewer on worktree changes.
   Returns {:verdict :approved|:needs-changes|:rejected, :comments [...]}"
  [{:keys [review-harness review-model]} worktree-path]
  (let [;; Get diff for context
        diff-result (process/sh ["git" "diff" "main" "--stat"]
                                {:dir worktree-path :out :string :err :string})
        diff-summary (:out diff-result)

        ;; Build review prompt
        review-prompt (str "Review the changes in this worktree.\n\n"
                           "Diff summary:\n" diff-summary "\n\n"
                           "Check for:\n"
                           "- Code correctness\n"
                           "- Matches the intended task\n"
                           "- No obvious bugs or issues\n\n"
                           "Respond with:\n"
                           "- APPROVED if changes are good\n"
                           "- NEEDS_CHANGES with bullet points of issues\n"
                           "- REJECTED if fundamentally wrong")

        abs-wt (.getAbsolutePath (io/file worktree-path))

        ;; Build command — cwd=worktree, no sandbox
        cmd (case review-harness
              :codex (cond-> ["codex" "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       review-model (into ["--model" review-model])
                       true (conj "--" review-prompt))
              :claude (cond-> ["claude" "-p" "--dangerously-skip-permissions"]
                        review-model (into ["--model" review-model])))

        ;; Run reviewer — cwd=worktree
        result (try
                 (if (= review-harness :claude)
                   (process/sh cmd {:dir abs-wt :in review-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-wt :out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))

        output (:out result)]

    {:verdict (cond
                (re-find #"(?i)\bAPPROVED\b" output) :approved
                (re-find #"(?i)\bREJECTED\b" output) :rejected
                :else :needs-changes)
     :comments (when (not= (:exit result) 0)
                 [(:err result)])
     :output output}))

(defn- run-fix!
  "Ask worker to fix issues based on reviewer feedback.
   Returns {:output string, :exit int}"
  [{:keys [harness model]} worktree-path feedback]
  (let [fix-prompt (str "The reviewer found issues with your changes:\n\n"
                        feedback "\n\n"
                        "Please fix these issues in the worktree.")

        abs-wt (.getAbsolutePath (io/file worktree-path))

        cmd (case harness
              :codex (cond-> ["codex" "exec"
                              "--dangerously-bypass-approvals-and-sandbox"
                              "--skip-git-repo-check"
                              "-C" abs-wt]
                       model (into ["--model" model])
                       true (conj "--" fix-prompt))
              :claude (cond-> ["claude" "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model])))

        result (try
                 (if (= harness :claude)
                   (process/sh cmd {:dir abs-wt :in fix-prompt :out :string :err :string})
                   (process/sh cmd {:dir abs-wt :out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

    {:output (:out result)
     :exit (:exit result)}))

(defn- merge-to-main!
  "Merge worktree changes to main branch"
  [wt-path wt-id worker-id project-root]
  (println (format "[%s] Merging changes to main" worker-id))
  (let [;; Commit in worktree if needed
        _ (process/sh ["git" "add" "-A"] {:dir wt-path})
        _ (process/sh ["git" "commit" "-m" (str "Work from " wt-id) "--allow-empty"]
                      {:dir wt-path})
        ;; Checkout main and merge (in project root, not worktree)
        checkout-result (process/sh ["git" "checkout" "main"]
                                    {:dir project-root :out :string :err :string})
        merge-result (when (zero? (:exit checkout-result))
                       (process/sh ["git" "merge" wt-id "--no-edit"]
                                   {:dir project-root :out :string :err :string}))]
    (and (zero? (:exit checkout-result))
         (zero? (:exit merge-result)))))

(defn- review-loop!
  "Run review loop: reviewer checks → if issues, fix & retry → back to reviewer.
   Returns {:approved? bool, :attempts int}"
  [worker wt-path worker-id]
  (if-not (and (:review-harness worker) (:review-model worker))
    ;; No reviewer configured, auto-approve
    {:approved? true :attempts 0}

    ;; Run review loop
    (loop [attempt 1]
      (println (format "[%s] Review attempt %d/%d" worker-id attempt max-review-retries))
      (let [{:keys [verdict output]} (run-reviewer! worker wt-path)]
        (case verdict
          :approved
          (do
            (println (format "[%s] Reviewer APPROVED" worker-id))
            {:approved? true :attempts attempt})

          :rejected
          (do
            (println (format "[%s] Reviewer REJECTED" worker-id))
            {:approved? false :attempts attempt})

          ;; :needs-changes
          (if (>= attempt max-review-retries)
            (do
              (println (format "[%s] Max review retries reached" worker-id))
              {:approved? false :attempts attempt})
            (do
              (println (format "[%s] Reviewer requested changes, fixing..." worker-id))
              (run-fix! worker wt-path output)
              (recur (inc attempt)))))))))

(defn execute-iteration!
  "Execute one iteration of work.

   Flow:
   1. Create worktree
   2. Run agent
   3. If reviewer configured: run review loop (fix → retry → reviewer)
   4. If approved: merge to main
   5. Cleanup worktree

   Returns {:status :done|:continue|:error, :task task-or-nil}"
  [worker iteration total-iterations]
  (let [worker-id (:id worker)
        project-root (System/getProperty "user.dir")
        wt-dir (format ".w%s-i%d" worker-id iteration)
        wt-branch (format "oompa/%s-i%d" worker-id iteration)

        ;; Create worktree
        _ (println (format "[%s] Starting iteration %d/%d" worker-id iteration total-iterations))
        wt-path (str project-root "/" wt-dir)]

    (try
      ;; Setup worktree (in project root) — dir starts with . but branch name must be valid
      (process/sh ["git" "worktree" "add" wt-dir "-b" wt-branch] {:dir project-root})

      ;; Build context
      (let [context (build-context)

            ;; Run agent
            {:keys [output exit done?]} (run-agent! worker wt-path context)]

        (cond
          ;; Agent signaled done
          done?
          (do
            (println (format "[%s] Received __DONE__ signal" worker-id))
            {:status :done})

          ;; Agent errored
          (not (zero? exit))
          (do
            (println (format "[%s] Agent error (exit %d): %s" worker-id exit (subs (or output "") 0 (min 200 (count (or output ""))))))
            {:status :error :exit exit})

          ;; Success - run review loop before merge
          :else
          (let [{:keys [approved?]} (review-loop! worker wt-path worker-id)]
            (if approved?
              (do
                (merge-to-main! wt-path wt-branch worker-id project-root)
                (println (format "[%s] Iteration %d/%d complete" worker-id iteration total-iterations))
                {:status :continue})
              (do
                (println (format "[%s] Iteration %d/%d rejected, discarding" worker-id iteration total-iterations))
                {:status :continue})))))

      (finally
        ;; Cleanup worktree (in project root)
        (process/sh ["git" "worktree" "remove" wt-dir "--force"] {:dir project-root})))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

(def ^:private max-wait-for-tasks 60)
(def ^:private wait-poll-interval 5)

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
  "Run worker loop until done or iterations exhausted.

   Returns final worker state."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id iterations]} worker]
    (println (format "[%s] Starting worker (%s:%s, %d iterations)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     iterations))

    ;; Backpressure: workers that can't create tasks wait for tasks to exist
    (when-not (:can-plan worker)
      (wait-for-tasks! id))

    (loop [iter 1
           completed 0]
      (if (> iter iterations)
        (do
          (println (format "[%s] Completed %d iterations" id completed))
          (assoc worker :completed completed :status :exhausted))

        (let [{:keys [status]} (execute-iteration! worker iter iterations)]
          (case status
            :done
            (do
              (println (format "[%s] Worker done after %d/%d iterations" id iter iterations))
              (assoc worker :completed iter :status :done))

            :error
            (do
              (println (format "[%s] Worker error at iteration %d/%d, continuing..." id iter iterations))
              (recur (inc iter) completed))

            :continue
            (recur (inc iter) (inc completed))))))))

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
