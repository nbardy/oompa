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
;; Worker State
;; =============================================================================

(defn create-worker
  "Create a worker config"
  [{:keys [id harness model iterations custom-prompt review-harness review-model]}]
  {:id id
   :harness (or harness :codex)
   :model model
   :iterations (or iterations 10)
   :custom-prompt custom-prompt
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
  [{:keys [harness model custom-prompt]} worktree-path context]
  (let [;; Load prompt (check config/prompts/ as default)
        prompt-content (or (agent/load-custom-prompt custom-prompt)
                           (agent/load-custom-prompt "config/prompts/worker.md")
                           "Goal: Match spec.md\nProcess: Create/claim tasks in tasks/{pending,current,complete}/*.edn\nMethod: Isolate changes to your worktree, commit and merge when complete")

        ;; Inject worktree and context
        full-prompt (str "Worktree: " worktree-path "\n"
                         "Task Status: " (:task_status context) "\n\n"
                         prompt-content)

        ;; Build command
        provider-cmd (agent/provider-command harness)
        cmd (case harness
              :codex (cond-> [provider-cmd "exec" "--full-auto" "--skip-git-repo-check"
                              "-C" worktree-path "--sandbox" "workspace-write"]
                       model (into ["--model" model])
                       true (conj "--" full-prompt))
              :claude (cond-> [provider-cmd "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model])))

        ;; Run agent
        result (try
                 (if (= harness :claude)
                   ;; Claude reads from stdin
                   (process/sh cmd {:in full-prompt :out :string :err :string})
                   ;; Codex takes prompt as arg
                   (process/sh cmd {:out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

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

        ;; Build command
        provider-cmd (agent/provider-command review-harness)
        cmd (case review-harness
              :codex (cond-> [provider-cmd "exec" "--full-auto" "--skip-git-repo-check"
                              "-C" worktree-path "--sandbox" "workspace-write"]
                       review-model (into ["--model" review-model])
                       true (conj "--" review-prompt))
              :claude (cond-> [provider-cmd "-p" "--dangerously-skip-permissions"]
                        review-model (into ["--model" review-model])))

        ;; Run reviewer
        result (try
                 (if (= review-harness :claude)
                   (process/sh cmd {:in review-prompt :out :string :err :string})
                   (process/sh cmd {:out :string :err :string}))
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

        cmd (case harness
              :codex (cond-> [(agent/provider-command :codex) "exec" "--full-auto" "--skip-git-repo-check"
                              "-C" worktree-path "--sandbox" "workspace-write"]
                       model (into ["--model" model])
                       true (conj "--" fix-prompt))
              :claude (cond-> [(agent/provider-command :claude) "-p" "--dangerously-skip-permissions"]
                        model (into ["--model" model])))

        result (try
                 (if (= harness :claude)
                   (process/sh cmd {:in fix-prompt :out :string :err :string})
                   (process/sh cmd {:out :string :err :string}))
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]

    {:output (:out result)
     :exit (:exit result)}))

(defn- merge-to-main!
  "Merge worktree changes to main branch"
  [wt-path wt-id worker-id]
  (println (format "[%s] Merging changes to main" worker-id))
  (let [;; Commit in worktree if needed
        _ (process/sh ["git" "add" "-A"] {:dir wt-path})
        _ (process/sh ["git" "commit" "-m" (str "Work from " wt-id) "--allow-empty"]
                      {:dir wt-path})
        ;; Checkout main and merge
        checkout-result (process/sh ["git" "checkout" "main"]
                                    {:out :string :err :string})
        merge-result (when (zero? (:exit checkout-result))
                       (process/sh ["git" "merge" wt-id "--no-edit"]
                                   {:out :string :err :string}))]
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
  [worker iteration]
  (let [worker-id (:id worker)
        wt-id (format ".w%s-i%d" worker-id iteration)

        ;; Create worktree
        _ (println (format "[%s] Starting iteration %d" worker-id iteration))
        wt-path (str (System/getProperty "user.dir") "/" wt-id)]

    (try
      ;; Setup worktree
      (process/sh ["git" "worktree" "add" wt-id "-b" wt-id])

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
            (println (format "[%s] Agent error (exit %d)" worker-id exit))
            {:status :error :exit exit})

          ;; Success - run review loop before merge
          :else
          (let [{:keys [approved?]} (review-loop! worker wt-path worker-id)]
            (if approved?
              (do
                (merge-to-main! wt-path wt-id worker-id)
                (println (format "[%s] Iteration %d complete" worker-id iteration))
                {:status :continue})
              (do
                (println (format "[%s] Iteration %d rejected, discarding" worker-id iteration))
                {:status :continue})))))

      (finally
        ;; Cleanup worktree
        (process/sh ["git" "worktree" "remove" wt-id "--force"])))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

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

    (loop [iter 1
           completed 0]
      (if (> iter iterations)
        (do
          (println (format "[%s] Completed %d iterations" id completed))
          (assoc worker :completed completed :status :exhausted))

        (let [{:keys [status]} (execute-iteration! worker iter)]
          (case status
            :done
            (do
              (println (format "[%s] Worker done after %d iterations" id iter))
              (assoc worker :completed iter :status :done))

            :error
            (do
              (println (format "[%s] Worker error at iteration %d, continuing..." id iter))
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
