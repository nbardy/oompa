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
   8. Reject __DONE__ — it is a planner-only signal; a worker that emits it is
      treated as an error outcome (workers signal CLAIM(...) /
      COMPLETE_AND_READY_FOR_MERGE / NEEDS_FOLLOWUP, never __DONE__).

   No separate orchestrator - workers self-organize.

   Drive model (2026-06 lean-design alignment): a GOAL drives a cycle, the
   per-cycle attempt budget only CAPS it. The inner attempt counter
   (historically called 'iterations') is NOT the driver of a cycle — it is the
   per-cycle BUDGET CAP (max-resumes) that bounds how long a single cycle may
   keep resuming. What advances work inside a cycle is the drive:
     - :resume drive (today, every harness): oompa owns continuation. When the
       agent ends a turn still working, the worker manually re-prompts it
       ('continue working' / nudge) and resumes the session. The budget cap is
       what stops an otherwise-unbounded resume chain.
     - :goal drive (future-real, codex `/goal` exec runtime): the goal loop owns
       continuation — the agent keeps driving toward the goal without an
       oompa-side re-prompt. Here the manual re-prompt is unnecessary; the
       budget (max-resumes) is the ONLY cap and the only guard against an
       unbounded codex goal. This is a documented STUB today (turn-drive falls
       back to :resume), so the manual re-prompt is still the live fallback."
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

(defn- resolve-prompt-path
  "Resolve a config/prompts-relative path to an existing file path.
   Tries the path as-is first (resolves relative to the worker's cwd, i.e. the
   git worktree it runs in), then falls back to <package-root>/<path>.
   Returns the resolved path string or nil if neither exists.

   This is the single base-path computation shared by every prompt load —
   load-prompt and load-framework-prompt both route through it so that
   _task_header.md, role prompts, and the _framework/*.md behavioral prompts all
   resolve identically from inside a worktree."
  [path]
  (let [as-is (io/file path)
        from-root (io/file (str package-root "/" path))]
    (cond
      (.exists as-is) path
      (.exists from-root) (str package-root "/" path)
      :else nil)))

(defn- load-prompt
  "Load a prompt file (with include-directive expansion), resolving its path
   through resolve-prompt-path so it shares the exact base-path computation
   (cwd, then package-root) every prompt load uses — see resolve-prompt-path."
  [path]
  (when-let [resolved (resolve-prompt-path path)]
    (agent/load-custom-prompt resolved)))

(defn- load-framework-prompt
  "Load a behavioral framework prompt from config/prompts/_framework/<name>.md and
   apply the standard {token} substitution.

   Resolves the file through resolve-prompt-path — the SAME base-path
   computation load-prompt uses for _task_header.md. Workers run inside git
   worktrees, not the repo root, so the package-root fallback is what makes
   these files resolve at runtime — a wrong base path would crash at runtime,
   not in unit tests.

   Reads the resolved file raw (no include expansion). agent/load-custom-prompt
   runs the content through expand-includes, which str/split-lines + str/join's
   the text and therefore STRIPS the trailing newline; these prompts are
   extracted byte-for-byte from inline string literals that did keep their
   trailing newline (notably reviewer.md), so a raw read is required to preserve
   that agent-facing text exactly. Framework prompts use no include directives.

   Throws (no silent fallback) if the framework file cannot be resolved; an
   unreadable framework prompt is a packaging error, not a recoverable state."
  [name tokens]
  (let [path (str "config/prompts/_framework/" name)
        resolved (resolve-prompt-path path)]
    (when (nil? resolved)
      (throw (ex-info (str "Framework prompt not found: " path)
                      {:name name :path path :package-root package-root})))
    (agent/tokenize (slurp resolved) tokens)))

(defn- tag-prompt
  "Prefix a prompt with its run-identity tag: \"[oompa:<swarm-id>:<id>] \".
   <swarm-id> defaults to \"unknown\" when absent. This tag is run IDENTITY
   (used to attribute agent-cli output back to a swarm/worker), NOT behavioral
   prompt text — it stays in code and is not a _framework template."
  [swarm-id id prompt]
  (str "[oompa:" (or swarm-id "unknown") ":" id "] " prompt))

(defn- turn-drive
  "Compute the per-turn drive keyword for harness/run-command!.
   First turn / no session → create (nil drive). Otherwise → :resume.

   Drive vs cap (lean-design intent): the drive is what continues a cycle; the
   per-cycle attempt budget (max-resumes) only CAPS it. Two drives exist:
     :resume — oompa owns continuation. The worker re-prompts ('continue
               working'/nudge) and resumes the session each attempt; the budget
               cap is what eventually stops the resume chain. This is the live
               path for every harness today.
     :goal   — the goal loop would own continuation, so the manual re-prompt
               becomes unnecessary and the budget (max-resumes) is the ONLY cap.

   Guard: a harness may advertise {:drive :goal} in the registry, but goal drive
   is a STUB pending the codex `/goal` exec runtime (codex exec runs one turn
   then shuts down; there is no external poll/resume loop). We do NOT half-build
   a goal loop here: a resuming turn always follows :resume semantics, and when
   the harness advertised :goal we note that once so logs explain the fallback.
   The manual re-prompt in the loop's :else branch is exactly that fallback for
   :resume; only when a real goal drive lands does it become a no-op.

   Returns :resume when resuming, nil when creating. Callers also keep passing
   the legacy :resume? boolean; run-command! accepts both and drops neither key."
  [worker-id harness resume?]
  (when resume?
    (when (= :goal (:drive (harness/get-config harness)))
      (println (format "[%s] goal drive pending codex runtime; defaulting to resume semantics (manual re-prompt fallback; budget remains the cap)" worker-id)))
    :resume))

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

(def ^:private default-max-wait-for-tasks 600)
;; The single per-cycle attempt (resume) BUDGET CAP — not a driver. CLAIM,
;; working, and NEEDS_FOLLOWUP continuations all count against it. The wrap-up
;; nudge fires once on the penultimate attempt (one attempt left), then the
;; cycle stops. Under a real goal drive this budget would be the ONLY guard
;; against an unbounded codex goal, which is why it must never be removed.
(def ^:private default-max-resumes 7)

(defn create-worker
  "Create a worker config.
   :prompts is a string or vector of strings — paths to prompt files.
   :can-plan when false, worker waits for tasks before starting (backpressure).
   :reasoning reasoning effort level (e.g. \"low\", \"medium\", \"high\") — codex only.
   :review-prompts paths to reviewer prompt files (loaded and concatenated for review).
   :wait-between seconds to sleep between cycles (nil or 0 = no wait).
   :max-wait-for-tasks max seconds a non-planner waits for tasks before giving up (default 600).
   :max-resumes the single per-cycle attempt (resume) budget (default 7). CLAIM,
     working, and NEEDS_FOLLOWUP continuations all count against it; the wrap-up
     nudge fires once on the penultimate attempt, then the cycle stops.
   :auto-merge-paths vector of path prefixes whose diffs auto-merge without a claim
     (default [\"tasks/\"]). Set e.g. [\"tasks/\" \"agent_notes/\"] to let a scientist
     role auto-merge note files alongside task JSONs."
  [{:keys [id swarm-id harness model max-cycles prompts can-plan reasoning
           reviewers wait-between
           max-wait-for-tasks max-resumes
           role can-claim-gpu auto-merge-paths]}]
  {:id id
   :swarm-id swarm-id
   :harness (or harness :codex)
   :model model
   :role role
   :can-claim-gpu (boolean can-claim-gpu)
   :max-cycles (or max-cycles 10)
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
   :max-resumes (or max-resumes default-max-resumes)
   :auto-merge-paths (if (seq auto-merge-paths) (vec auto-merge-paths) ["tasks/"])
   :completed 0
   :status :idle})

;; =============================================================================
;; Task Execution
;; =============================================================================

(def ^:private max-review-retries 3)

;; Nudge prompt injected on the penultimate resume attempt (one attempt left).
;; Gives the agent one final chance to produce something mergeable before the
;; per-cycle attempt budget (max-resumes) stops the cycle. Behavioral text lives
;; in config/prompts/_framework/nudge.md; loaded fresh so roles can edit it live.
(defn- nudge-prompt
  []
  (load-framework-prompt "nudge.md" {}))

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

(defn- worker-role
  [worker]
  (some-> (:role worker) str))

(defn- task-role-hint
  [task]
  (some-> (:role_hint task) str))

(defn- gpu-heavy-task?
  [task]
  (true? (:gpu_heavy task)))

(defn- worker-can-claim-gpu?
  [worker]
  (true? (:can-claim-gpu worker)))

(defn- task-dependencies
  [task]
  (->> (or (:depends_on task) (:dependsOn task) [])
       (map str)
       (remove str/blank?)
       vec))

(defn- completed-task-ids
  []
  (->> (tasks/list-complete)
       (map :id)
       (remove nil?)
       set))

(defn- task-claim-denial
  "Return nil if worker may claim task, or a reason map if the claim is invalid.

   Role hints are advisory to agents, but the framework must enforce them during
   file moves. Otherwise idle CTO/scientist workers can steal role-scoped GPU
   follow-ups before the correct engineer claims them."
  [worker task]
  (let [role (worker-role worker)
        hint (task-role-hint task)
        deps (task-dependencies task)
        complete (delay (completed-task-ids))
        missing-deps (when (seq deps)
                       (vec (remove @complete deps)))]
    (cond
      (seq missing-deps)
      {:reason "dependency-missing" :missing-dependencies (str/join "," missing-deps)}

      (and role hint (not= role hint))
      {:reason "role-mismatch" :worker-role role :task-role-hint hint}

      (and (gpu-heavy-task? task)
           (or (false? (:can-claim-gpu worker))
               (and role (not (worker-can-claim-gpu? worker)))))
      {:reason "gpu-restricted" :worker-role role}

      :else nil)))


(defn- execute-claims!
  "Execute CLAIM signal: attempt to claim each task ID from pending/.
   Returns {:claimed [ids], :failed [ids], :resume-prompt string}."
  [worker claim-ids]
  (let [results (tasks/claim-by-ids! claim-ids {:claim-denial-fn #(task-claim-denial worker %)})
        claimed (filterv #(= :claimed (:status %)) results)
        failed (filterv #(not= :claimed (:status %)) results)
        claimed-ids (mapv :id claimed)
        failed-labels (mapv (fn [{:keys [id status reason worker-role task-role-hint missing-dependencies]}]
                              (str id
                                   " (" (name status)
                                   (when reason (str ":" reason))
                                   (when missing-dependencies (str ", missing=" missing-dependencies))
                                   (when task-role-hint (str ", task-role=" task-role-hint))
                                   (when worker-role (str ", worker-role=" worker-role))
                                   ")"))
                            failed)
        context (build-context)
        claimed-line (if (seq claimed-ids)
                       (str "Claimed: " (str/join ", " claimed-ids) "\n")
                       "No tasks were successfully claimed.\n")
        failed-line (if (seq failed-labels)
                      (str "Rejected, already taken, or not found: "
                           (str/join ", " failed-labels) "\n")
                      "")
        pending-block (if (str/blank? (:pending_tasks context))
                        "(none)"
                        (:pending_tasks context))
        outcome-line (if (seq claimed-ids)
                       "Work on your claimed tasks. Signal COMPLETE_AND_READY_FOR_MERGE when done."
                       "No claims succeeded. CLAIM different tasks. If you cannot finish a mergeable artifact after trying hard, signal NEEDS_FOLLOWUP with a short explanation.")
        prompt (load-framework-prompt "claim_results.md"
                                      {:claimed_line claimed-line
                                       :failed_line failed-line
                                       :task_status (:task_status context)
                                       :pending_block pending-block
                                       :outcome_line outcome-line})]
    {:claimed claimed-ids
     :failed (mapv :id failed)
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
                            str/trim)
        ownership-line (if (seq claimed-ids)
                         (str "You still own these claimed tasks: "
                              (str/join ", " (sort claimed-ids))
                              "\n\n")
                         "You do not currently own any claimed tasks.\n\n")
        explanation-block (if (seq explanation)
                            (str "Your previous explanation:\n"
                                 explanation
                                 "\n\n")
                            "")
        pending-block (if (str/blank? (:pending_tasks context))
                        "(none)"
                        (:pending_tasks context))]
    (load-framework-prompt "needs_followup.md"
                           {:ownership_line ownership-line
                            :explanation_block explanation-block
                            :task_status (:task_status context)
                            :pending_block pending-block})))

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
                 (load-framework-prompt "resume.md"
                                        {:task_status (:task_status context)
                                         :pending_tasks (:pending_tasks context)})

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
                                          (load-framework-prompt "worker_default.md" {})))]
                   (str task-header "\n"
                        "Task Status: " (:task_status context) "\n"
                        "Pending: " (:pending_tasks context) "\n\n"
                        user-prompts)))

        tagged-prompt (tag-prompt swarm-id id prompt)
        abs-worktree (.getAbsolutePath (io/file worktree-path))

        result (try
                 (harness/run-command! harness
                                       {:cwd abs-worktree :model model :reasoning reasoning
                                        :session-id session-id :resume? resume?
                                        :drive (turn-drive id harness resume?)
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
     :merge-notes (agent/parse-merge-notes output)
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

        ;; Recently MERGED subject lines on main (not the worker's own branch
        ;; commits — use the `main` ref). This gives the reviewer pacing context
        ;; so it can flag a diff that duplicates or churns work already merged.
        merged-log (-> (process/sh ["git" "log" "-n" "10" "--format=%s" "main"]
                                   {:dir worktree-path :out :string :err :string})
                       :out str/trim)
        merged-section (when (seq merged-log)
                         (str "\n## Recently merged work on main (last 10)\n\n"
                              merged-log
                              "\n\n"))

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
                               review-base (or custom-prompt
                                                "Review the changes in this worktree.\nFocus on architecture and design, not style.\n")
                               review-body (load-framework-prompt
                                             "reviewer.md"
                                             {:base review-base
                                              :diff_content diff-content
                                              :merged_section (or merged-section "")
                                              :history_block (or history-block "")})
                               review-prompt (tag-prompt swarm-id id review-body)
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
        feedback-text (if (> (count all-feedback) 1)
                        (str "The reviewer has given feedback across " (count all-feedback) " rounds.\n"
                             "Fix ALL outstanding issues:\n\n"
                             (->> all-feedback
                                  (map-indexed (fn [i fb]
                                    (str "--- Round " (inc i) " ---\n" fb)))
                                  (str/join "\n\n")))
                        (str "The reviewer found issues with your changes:\n\n"
                             (first all-feedback)))
        fix-prompt (tag-prompt swarm-id id
                               (load-framework-prompt "fix.md" {:feedback_text feedback-text}))

        abs-wt (.getAbsolutePath (io/file worktree-path))

        result (try
                 (harness/run-command! harness
                                       (cond-> {:cwd abs-wt :model model :prompt fix-prompt}
                                         ;; Resume existing session so the worker keeps
                                         ;; full context of the code it already wrote.
                                         session-id (assoc :session-id session-id
                                                           :resume? true
                                                           :drive (turn-drive id harness true))))
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
              resolve-prompt (tag-prompt (:swarm-id worker) worker-id
                                         (load-framework-prompt "merge_conflict_resolve.md"
                                                                {:error last-error
                                                                 :branch_log branch-log
                                                                 :main_log main-log
                                                                 :diff_stat diff-stat}))
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
    ;; Deterministic worktree setup: if the repo ships scripts/worktree-bootstrap.sh,
    ;; the FRAMEWORK runs it here — before the agent ever starts — instead of
    ;; trusting every model to obey a "STEP ZERO: run this" prompt line. A missed
    ;; bootstrap means no node_modules, which burns the whole cycle budget on a
    ;; doomed npm install (documented swarm-killer). Loud failure, no fallback:
    ;; a broken bootstrap fails the cycle here with the script's stderr.
    ;; NB: invoke the WORKTREE's copy of the script, not the primary's — it
    ;; self-locates via BASH_SOURCE, so the primary's copy would resolve
    ;; WORKTREE_ROOT to the primary checkout and silently no-op.
    (let [bootstrap (str wt-path "/scripts/worktree-bootstrap.sh")]
      (when (.exists (java.io.File. bootstrap))
        (let [result (process/sh [bootstrap] {:dir wt-path :out :string :err :string})]
          (when-not (zero? (:exit result))
            (throw (ex-info (str "worktree bootstrap failed (exit " (:exit result) "): "
                                 (str/trim (str (:err result) " " (:out result))))
                            {:dir wt-dir :script bootstrap}))))))
    {:dir wt-dir :branch wt-branch :path wt-path}))

(defn- detect-claimed-tasks
  "Do not infer ownership from shared current/ diffs.

   Multiple workers run concurrently, so another worker can move a task from
   pending/ to current/ while this worker is inside an LLM call. Treating that
   shared-state diff as this worker's claim lets one merge complete unrelated
   workers' tasks. The framework records ownership only from explicit CLAIM
   results returned by execute-claims!."
  [_pre-current-ids]
  #{})

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
  [swarm-id worker-id cycle attempt _completed start-ms session-id
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
      (cond-> {:attempt attempt
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
                              :no-claim
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

(defn- preserve-merge-failure-salvage!
  "On merge failure, keep the approved work reachable instead of destroying it.

   Audit (run 9f004a39): worker w7's round-2 APPROVED deliverable (an
   art-direction doc + four visual fixes, 2.5h of work) ended outcome
   merge-failed and its worktree was cleaned up unconditionally — the content
   exists in no git ref today. Never cleanup on merge failure; pin the branch
   head under a salvage ref (create-iteration-worktree! force-deletes stale
   oompa/* branches on restart, so the worktree branch alone is not safe) and
   leave the worktree in place.

   Creates refs/heads/salvage/{swarm-id}-{worker-id}-c{cycle} at the worktree
   branch head. Prints the surviving worktree path and ref name. A failure to
   create the ref is reported loudly (no silent fallback)."
  [project-root swarm-id worker-id cycle wt-state]
  (let [salvage-branch (format "salvage/%s-%s-c%d" (or swarm-id "unknown") worker-id cycle)
        result (process/sh ["git" "branch" "-f" salvage-branch (:branch wt-state)]
                           {:dir project-root :out :string :err :string})]
    (if (zero? (:exit result))
      (println (format "[%s] Merge failed — work preserved at ref refs/heads/%s; worktree left for salvage: %s"
                       worker-id salvage-branch (:path wt-state)))
      (println (format "[%s] Merge failed — could NOT create salvage ref %s (%s); worktree left for salvage: %s"
                       worker-id salvage-branch
                       (str/trim (str (:err result))) (:path wt-state))))))

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
   :review-rounds, :merged-commit, and optional :notes."
  [project-root worker-id review-rounds & {:keys [merge-notes]}]
  (let [commit-hash (get-head-hash project-root)
        complete-dir (io/file project-root "tasks" "complete")]
    (when (.exists complete-dir)
      (doseq [f (.listFiles complete-dir)]
        (when (str/ends-with? (.getName f) ".json")
          (try
            (let [task (json/parse-string (slurp f) true)]
              (when-not (:completed-by task)
                (spit f (str (json/generate-string
                              (cond-> (assoc task
                                             :completed-by worker-id
                                             :completed-at (str (java.time.Instant/now))
                                             :review-rounds (or review-rounds 0)
                                             :merged-commit (or commit-hash "unknown"))
                                merge-notes (assoc :notes merge-notes))
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
  [wt-path wt-id worker-id project-root review-rounds claimed-task-ids
   & {:keys [merge-notes]}]
  (locking merge-lock
    (println (format "[%s] Merging changes to main" worker-id))
    (let [commit-msg (if merge-notes
                       (str "Work from " wt-id "\n\n" merge-notes)
                       (str "Work from " wt-id))
          ;; Commit in worktree if needed (no-op if already committed)
          _ (process/sh ["git" "add" "-A"] {:dir wt-path})
          _ (process/sh ["git" "commit" "-m" commit-msg]
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
          (annotate-completed-tasks! project-root worker-id review-rounds :merge-notes merge-notes)
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
  (load-framework-prompt "merge_authorization.md"
                         {:project_root project-root
                          :wt_branch wt-branch}))

(defn run-merge-agent!
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
                                            :drive (turn-drive worker-id (:harness worker) true)
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
  [project-root worker-id review-rounds claimed-task-ids sha
   & {:keys [merge-notes]}]
  (let [completed (when (seq claimed-task-ids)
                    (tasks/complete-by-ids! claimed-task-ids))
        completed-count (count (or completed []))]
    (when (seq completed)
      (println (format "[%s] Completed %d task(s): %s"
                       worker-id completed-count (str/join ", " completed))))
    (annotate-completed-tasks! project-root worker-id review-rounds :merge-notes merge-notes)
    completed-count))

(defn- auto-mergeable-diff?
  "Check if every changed file is under one of the worker's auto-merge path
   prefixes (default [\"tasks/\"]). When true, the worker's diff auto-merges
   via merge-agent without requiring a task claim.

   Set :auto-merge-paths on a worker config to widen this — e.g. a scientist
   role with [\"tasks/\" \"agent_notes/\"] can write a note alongside its task
   JSONs and have both auto-merge."
  [wt-path paths]
  (let [prefixes (if (seq paths) (vec paths) ["tasks/"])
        result (process/sh ["git" "diff" "main" "--name-only"]
                           {:dir wt-path :out :string :err :string})
        files (when (zero? (:exit result))
                (->> (str/split-lines (:out result))
                     (remove str/blank?)))]
    (and (seq files)
         (every? (fn [f] (some #(str/starts-with? f %) prefixes)) files))))

(defn- diff-file-names
  "Get list of changed file names vs main."
  [wt-path]
  (let [result (process/sh ["git" "diff" "main" "--name-only"]
                           {:dir wt-path :out :string :err :string})]
    (when (zero? (:exit result))
      (->> (str/split-lines (:out result))
           (remove str/blank?)
           vec))))

(defn review-loop!
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
;; Merge signaled with changes but no claimed tasks is an agent PROTOCOL error,
;; not a worker-fatal condition. Audit (runs 80a33337/9f004a39): treating the
;; first occurrence as fatal killed 32 of 36 worker-lifetimes and burned 11.8h
;; (32% of all worker time). The cycle is recycled instead; only after this
;; many occurrences does the worker fall through to the old fatal stop, so a
;; pathological agent cannot loop forever.
(def ^:private max-merge-no-claim-events 3)

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

   A cycle is one complete unit of work: claim → implement → review → merge/reject.
   max-cycles controls how many completed cycles before the worker stops.

   max-resumes (default 7) is the per-cycle BUDGET CAP, not the driver of a
   cycle. What drives a cycle is the drive (see turn-drive): under :resume the
   worker re-prompts/resumes each attempt and the budget caps that chain; under
   a real :goal drive the goal loop would drive and the budget would be the ONLY
   cap. CLAIM, working, and NEEDS_FOLLOWUP all count as attempts against this
   budget. The inner attempt counter is the cap, never the work driver."
  [worker]
  (tasks/ensure-dirs!)
  (let [{:keys [id max-cycles swarm-id wait-between
                max-wait-for-tasks max-resumes]} worker
        cycle-cap (or max-cycles 10)
        ;; Cheap nil-guard kept per the correctness critic: a worker map built
        ;; outside create-worker may omit :max-resumes.
        resume-cap (or max-resumes default-max-resumes)
        project-root (System/getProperty "user.dir")]
    (println (format "[%s] Starting worker (%s:%s%s, max_cycle=%d, max_resumes=%d%s)"
                     id
                     (name (:harness worker))
                     (or (:model worker) "default")
                     (if (:reasoning worker) (str ":" (:reasoning worker)) "")
                     cycle-cap
                     resume-cap
                     (if wait-between (format ", %ds between" wait-between) "")))

    (when (and (not (:can-plan worker))
               (not (pos? (tasks/pending-count)))
               (not (pos? (tasks/current-count))))
      (wait-for-tasks! id max-wait-for-tasks))

    (loop [cycle 1
           attempt 1
           completed 0
           consec-errors 0
           metrics {:merges 0 :rejections 0 :errors 0 :recycled 0 :review-rounds-total 0 :claims 0
                    :merge-no-claim 0}
           session-id nil
           wt-state nil
           claimed-ids #{}
           claim-resume-prompt nil
           signals []]
      (let [finish (fn [status]
                     (assoc worker :completed completed
                                   :cycles-completed (dec cycle)
                                   :status status
                                   :merges (:merges metrics)
                                   :rejections (:rejections metrics)
                                   :errors (:errors metrics)
                                   :recycled (:recycled metrics)
                                   :review-rounds-total (:review-rounds-total metrics)
                                   :claims (:claims metrics)))]
        (cond
          (>= completed cycle-cap)
          (do
            (when wt-state
              (when (seq claimed-ids)
                (recycle-task-id-set! id claimed-ids))
              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
            (println (format "[%s] Completed %d/%d cycles (%d merges, %d claims, %d rejections, %d errors, %d recycled)"
                             id completed cycle-cap
                             (:merges metrics) (:claims metrics) (:rejections metrics) (:errors metrics) (:recycled metrics)))
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
            (emit-cycle-log! swarm-id id cycle attempt (inc completed) (now-ms) session-id
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

            ;; Hard cap on resumes within a cycle — if attempt exceeds resume-cap,
            ;; recycle claims and move to next cycle.
            (if (> attempt resume-cap)
              (let [recycled (recycle-task-id-set! id claimed-ids)
                    metrics (update metrics :recycled + (count (or recycled [])))]
                (println (format "[%s] Resume cap reached (%d attempts in cycle %d), moving on" id (dec attempt) cycle))
                (when wt-state
                  (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state)))
                (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil []))

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
                        (recur (inc cycle) 1 completed errors metrics nil nil #{} nil []))))

                (let [resume? (or (some? session-id) (some? claim-resume-prompt))
                      cycle-start-ms (now-ms)
                      cycle-timing (init-cycle-timing)
                      pre-current-ids (tasks/current-task-ids)
                      _ (println (format "[%s] %s cycle %d/%d (attempt %d/%d)"
                                         id
                                         (if (= attempt 1) "Starting" "Resuming")
                                         (inc completed) cycle-cap attempt resume-cap))
                      context (build-context)
                      agent-start-ms (now-ms)
                      {:keys [output exit done? merge? merge-notes needs-followup? claim-ids parse-warning raw-snippet] :as agent-result}
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
                              (emit-cycle-log! swarm-id id cycle attempt (inc completed) cycle-start-ms new-session-id
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
                            (recur (inc cycle) 1 (inc completed) errors metrics nil nil #{} nil []))))

                    (and (seq claim-ids) (not merge?) (not done?))
                    (let [_ (println (format "[%s] CLAIM signal: %s" id (str/join ", " claim-ids)))
                          {:keys [claimed resume-prompt]} (execute-claims! worker claim-ids)
                          new-claimed-ids (into active-claimed-ids claimed)
                          metrics (update metrics :claims + (count claimed))]
                      (println (format "[%s] Claimed %d/%d tasks" id (count claimed) (count claim-ids)))
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome (if (seq claimed) :claimed :no-claim)
                                        :claimed-task-ids (vec claimed)})
                      (if (seq claimed)
                        (recur cycle (inc attempt) completed 0 metrics new-session-id wt-state
                               new-claimed-ids resume-prompt signals)
                        (do
                          (println (format "[%s] No claims succeeded; ending cycle without resuming unowned work" id))
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil []))))

                    merge?
                    (if (and (worktree-has-changes? (:path wt-state))
                             (not (seq active-claimed-ids))
                             (not (auto-mergeable-diff? (:path wt-state)
                                                        (:auto-merge-paths worker))))
                      ;; Protocol error: merge signaled with changes but no
                      ;; claimed tasks. Audit (runs 80a33337/9f004a39): stopping
                      ;; the worker on the FIRST occurrence killed 32 of 36
                      ;; worker-lifetimes (11.8h = 32% of all worker time).
                      ;; Recycle the cycle like the rejection path below —
                      ;; leaving the worktree for salvage — and only stop after
                      ;; max-merge-no-claim-events occurrences.
                      (let [occurrences (inc (:merge-no-claim metrics))
                            metrics (-> metrics
                                        (assoc :merge-no-claim occurrences)
                                        (update :errors inc))
                            fatal? (>= occurrences max-merge-no-claim-events)]
                        (println (format "[%s] Merge signaled with changes but no claimed tasks (occurrence %d/%d); leaving worktree for salvage%s"
                                         id occurrences max-merge-no-claim-events
                                         (if fatal? " and stopping worker" " and recycling cycle")))
                        (emit!
                                         {:timing-ms cycle-timing
                                          :outcome :error
                                          :claimed-task-ids []
                                          :error-snippet "merge signaled with changes but no claimed tasks"})
                        (if fatal?
                          (finish :error)
                          (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil [])))
                      (if (worktree-has-changes? (:path wt-state))
                      (if (auto-mergeable-diff? (:path wt-state) (:auto-merge-paths worker))
                        (let [all-claimed active-claimed-ids]
                          (println (format "[%s] Auto-mergeable diff (under %s), auto-merging via agent"
                                           id
                                           (str/join ", " (or (:auto-merge-paths worker) ["tasks/"]))))
                          (let [merge-result (run-merge-agent! worker (:path wt-state) (:branch wt-state) project-root new-session-id id)
                                merged? (:ok? merge-result)
                                sha (:sha merge-result)
                                _ (when merged? (complete-merge! project-root id 0 all-claimed sha :merge-notes merge-notes))
                                recycled (when-not merged? (recycle-task-id-set! id all-claimed))
                                metrics (cond-> metrics
                                          merged? (update :merges inc)
                                          (seq recycled) (update :recycled + (count recycled)))]
                            (println (format "[%s] Cycle %d/%d complete" id (inc completed) cycle-cap))
                            (emit!
                                             {:timing-ms cycle-timing
                                              :outcome (if merged? :merged :merge-failed)
                                              :merge-sha sha
                                              :claimed-task-ids (vec all-claimed)
                                              :recycled-tasks (seq recycled)
                                              :review-rounds 0})
                            ;; Cleanup ONLY on successful merge — a failed merge
                            ;; must leave the work reachable (see
                            ;; preserve-merge-failure-salvage!, audit run
                            ;; 9f004a39 w7-c2 lost-work incident).
                            (if merged?
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (preserve-merge-failure-salvage! project-root swarm-id id cycle wt-state))
                            (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil [])))
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
                                  _ (when merged? (complete-merge! project-root id (or attempts 0) all-claimed sha :merge-notes merge-notes))
                                  recycled (when-not merged? (recycle-task-id-set! id all-claimed))
                                  metrics (cond-> metrics
                                            merged? (update :merges inc)
                                            (seq recycled) (update :recycled + (count recycled)))]
                              (println (format "[%s] Cycle %d/%d complete" id (inc completed) cycle-cap))
                              (emit!
                                               {:timing-ms cycle-timing
                                                :outcome (if merged? :merged :merge-failed)
                                                :merge-sha sha
                                                :claimed-task-ids (vec all-claimed)
                                                :recycled-tasks (seq recycled)
                                                :review-rounds (or attempts 0)})
                              ;; Cleanup ONLY on successful merge. Audit run
                              ;; 9f004a39: worker w7's round-2 APPROVED
                              ;; deliverable was destroyed here by an
                              ;; unconditional cleanup after merge failure.
                              (if merged?
                                (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                                (preserve-merge-failure-salvage! project-root swarm-id id cycle wt-state))
                              (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil []))
                            (let [recycled (recycle-active-claims! id claimed-ids mv-claimed-tasks)
                                  metrics (update metrics :recycled + (count recycled))]
                              (println (format "[%s] Cycle %d/%d rejected" id (inc completed) cycle-cap))
                              (emit!
                                               {:timing-ms cycle-timing
                                                :outcome :rejected
                                                :claimed-task-ids (vec active-claimed-ids)
                                                :recycled-tasks (seq recycled)
                                                :review-rounds (or attempts 0)})
                              (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                              (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil [])))))
                      ;; Worker signaled merge with no code diff. If they claimed
                      ;; tasks, they verified the work is already done — complete them.
                      ;; (Tasks live outside the worktree at ../tasks/, so mv is
                      ;; invisible to git diff. Framework must own this transition.)
                      (if-not (seq active-claimed-ids)
                        (do
                          (println (format "[%s] Merge signaled with no changes and no claimed tasks; stopping worker" id))
                          (emit!
                                           {:timing-ms cycle-timing
                                            :outcome :no-claim
                                            :claimed-task-ids []})
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (finish :completed))
                        (let [completed-ids (tasks/complete-by-ids! (vec active-claimed-ids))]
                          (when (seq completed-ids)
                            (println (format "[%s] No-diff merge: completing %d verified task(s): %s"
                                             id (count completed-ids) (str/join ", " completed-ids)))
                            (annotate-completed-tasks! project-root id 0 :merge-notes merge-notes))
                          (emit!
                                           {:timing-ms cycle-timing
                                            :outcome :no-changes
                                            :claimed-task-ids (vec active-claimed-ids)})
                          (cleanup-worktree! project-root (:dir wt-state) (:branch wt-state))
                          (recur (inc cycle) 1 (inc completed) 0 metrics nil nil #{} nil []))))
                      )

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

                    ;; NEEDS_FOLLOWUP keeps ownership and continues the SAME cycle
                    ;; with a follow-up prompt. There is no separate followup
                    ;; budget: the continuation counts against the single
                    ;; per-cycle attempt budget (max-resumes). When that budget
                    ;; is spent, the (> attempt resume-cap) guard at the top of
                    ;; the loop recycles the claims and moves to the next cycle.
                    needs-followup?
                    (let [summary (subs (or output "") 0 (min 240 (count (or output ""))))
                          followup-prompt (build-needs-followup-prompt active-claimed-ids output)]
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :needs-followup
                                        :claimed-task-ids (vec active-claimed-ids)
                                        :error-snippet summary})
                      (println (format "[%s] NEEDS_FOLLOWUP signal; continuing cycle with follow-up prompt (attempt %d/%d)"
                                       id attempt resume-cap))
                      (recur cycle (inc attempt) completed 0 metrics new-session-id wt-state
                             active-claimed-ids followup-prompt signals))

                    ;; Working without a signal — resume the session. This is the
                    ;; :resume-drive FALLBACK: oompa manually re-prompts ('continue
                    ;; working' / nudge) and resumes, because today's goal drive is
                    ;; a stub (turn-drive falls back to :resume). Under a real :goal
                    ;; drive the goal loop would own this continuation and the
                    ;; manual re-prompt would not fire — but the budget cap below
                    ;; stays either way, as the only guard against an unbounded run.
                    ;; The cycle's single attempt budget (max-resumes) is the CAP,
                    ;; not the driver: the (> attempt resume-cap) guard at the top
                    ;; of the loop recycles claims and moves on when it is spent.
                    ;; On the penultimate attempt (one attempt left, i.e.
                    ;; attempt == resume-cap - 1) inject the wrap-up nudge once so
                    ;; the agent gets a final chance to produce something mergeable.
                    :else
                    (let [penultimate? (= attempt (dec resume-cap))]
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
                      (if penultimate?
                        (println (format "[%s] Working... attempt %d/%d, nudging agent to wrap up" id attempt resume-cap))
                        (println (format "[%s] Working... (will resume, attempt %d/%d)" id attempt resume-cap)))
                      (emit!
                                       {:timing-ms cycle-timing
                                        :outcome :working
                                        :claimed-task-ids (vec active-claimed-ids)})
                      (recur cycle (inc attempt) completed 0 metrics new-session-id wt-state
                             active-claimed-ids (when penultimate? (nudge-prompt)) signals)))))))))))))

;; =============================================================================
;; Multi-Worker Execution
;; =============================================================================

(defn- swarm-pid-alive?
  "Probe pid liveness by shelling out to `kill -0` and checking the exit code.
   (ProcessHandle/of is not available in babashka.)"
  [pid]
  (zero? (:exit (process/sh ["kill" "-0" (str pid)]
                            {:out :string :err :string}))))

(defn- live-prior-swarms
  "Scan runs/*/started.json entries that lack a sibling stopped.json and whose
   recorded pid is still alive. Returns [{:swarm-id .. :pid ..}].
   Excludes the current process's own run entry — cmd-swarm writes started.json
   before workers launch, so the caller's own pid always shows up here."
  []
  (let [self-pid (.pid (java.lang.ProcessHandle/current))]
    (->> (or (runs/list-runs) [])
         (keep (fn [rid]
                 (when (nil? (runs/read-stopped rid))
                   (when-let [started (runs/read-started rid)]
                     (let [pid (:pid started)]
                       (when (and pid
                                  (not= (long pid) (long self-pid))
                                  (swarm-pid-alive? pid))
                         {:swarm-id rid :pid pid}))))))
         vec)))

(defn ensure-single-swarm!
  "Refuse to start when another live swarm is already running against this repo.

   Audit (runs 80a33337/9f004a39): two swarms overlapped 2h21m on one queue,
   manufacturing stale-base diffs (18/22 round-1 review rejections),
   double-claims, and a 68-minute duplicate implementation of an already-merged
   task. Startup previously only printed a stale-claims warning.

   Escape hatches: force? true (CLI --force) or env OOMPA_FORCE_SWARM=1.
   Throws ex-info naming the live run id and pid when refusing."
  [force?]
  (let [forced? (or force? (= "1" (System/getenv "OOMPA_FORCE_SWARM")))
        live (live-prior-swarms)]
    (when (seq live)
      (if forced?
        (println (format "WARNING: single-swarm lock bypassed (--force / OOMPA_FORCE_SWARM=1); live swarm(s): %s"
                         (str/join ", " (map #(format "%s (pid %s)" (:swarm-id %) (:pid %)) live))))
        (let [{:keys [swarm-id pid]} (first live)]
          (println (format "ERROR: swarm %s (pid %s) appears to be running against this repo." swarm-id pid))
          (println "       Two overlapping swarms on one queue manufacture stale-base diffs, double-claims,")
          (println "       and duplicate implementations (audit: runs 80a33337/9f004a39, 2h21m overlap).")
          (println (format "       Stop it first (kill %s), or re-run with --force / OOMPA_FORCE_SWARM=1" pid))
          (println "       if you are certain it is not doing real work.")
          (throw (ex-info (format "another swarm is already running: %s (pid %s) — stop it or use --force / OOMPA_FORCE_SWARM=1"
                                  swarm-id pid)
                          {:live-swarms live})))))))

(defn- worker-terminal-outcome
  "Slim JSON-serializable terminal record for one worker's final state,
   persisted into stopped.json so a dead run is diagnosable from the event
   log alone (audit: runs 80a33337/9f004a39 recorded reason \"completed\",
   error nil, while every worker had terminated in an error state)."
  [result]
  (cond-> {:id (or (:id result) "unknown")
           :status (name (or (:status result) :unknown))}
    (contains? result :cycles-completed) (assoc :cycles-completed (:cycles-completed result))
    (contains? result :merges) (assoc :merges (:merges result))
    (contains? result :errors) (assoc :errors (:errors result))
    (contains? result :rejections) (assoc :rejections (:rejections result))
    (contains? result :recycled) (assoc :recycled (:recycled result))
    (contains? result :claims) (assoc :claims (:claims result))
    (:error result) (assoc :error (:error result))))

(defn- stopped-reason
  "Honest stop reason for stopped.json. \"completed\" is reserved for a
   genuinely drained queue; when ZERO workers ended :completed and pending
   tasks remain, the swarm died, and the reason says so."
  [results pending-count]
  (let [any-completed? (boolean (some #(= :completed (:status %)) results))]
    (if (and (not any-completed?) (pos? pending-count))
      :workers-exhausted
      :completed)))

(defn run-workers!
  "Run multiple workers in parallel.
   Writes stopped event to runs/{swarm-id}/stopped.json on completion.

   Arguments:
     workers - seq of worker configs
     opts    - optional {:force? bool} — bypass the single-swarm startup lock
               (equivalent to CLI --force / env OOMPA_FORCE_SWARM=1)

   Returns seq of final worker states."
  ([workers] (run-workers! workers {}))
  ([workers {:keys [force?]}]
  ;; Single-swarm lock — refuse to overlap a live prior swarm (Defect: two
  ;; overlapping swarms manufactured stale-base diffs and double-claims).
  (ensure-single-swarm! force?)
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

          ;; Write stopped event — per-worker terminal outcomes plus an HONEST
          ;; reason. Audit (runs 80a33337/9f004a39): both dead runs recorded
          ;; reason "completed", error nil, although every worker terminated in
          ;; an error state and pending tasks remained — nothing alerted for
          ;; ~20h. "completed" is now reserved for a genuinely drained queue.
          (when swarm-id
            (let [worker-outcomes (mapv worker-terminal-outcome results)
                  pending (tasks/pending-count)
                  reason (stopped-reason results pending)]
              (runs/write-stopped! swarm-id reason
                                   :worker-outcomes worker-outcomes
                                   :pending-count pending)
              (when (= :workers-exhausted reason)
                (println (format "\nWARNING: all %d workers terminated without completing and %d task(s) remain pending."
                                 (count results) pending)))
              (println (format "\nStopped event written to runs/%s/stopped.json (reason: %s)"
                               swarm-id (name reason)))))

          results))))))

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
                             (load-framework-prompt
                               "planner_tail.md"
                               {:task_status (:task_status context)
                                :pending_tasks (:pending_tasks context)
                                :max_new_tasks (- max-pending pending-before)}))
            tagged-prompt (tag-prompt swarm-id "planner" prompt-text)
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
