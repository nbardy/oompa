(ns agentnet.review
  "Propose-review-fix loop management.

   The review loop orchestrates the back-and-forth between proposer and
   reviewer agents until changes are approved or max attempts exhausted.

   Flow:
     1. Proposer makes changes
     2. Reviewer evaluates changes
     3. If approved -> exit loop, ready to merge
     4. If needs-changes -> proposer fixes based on feedback
     5. Repeat until approved or max attempts

   Design:
     - Loop state is a pure data structure (ReviewLoop)
     - Each step returns new state (functional)
     - Side effects (agent calls) isolated to step functions
     - Configurable max attempts prevents infinite loops"
  (:require [agentnet.schema :as schema]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [clojure.string :as str]))

;; =============================================================================
;; Function Specs
;; =============================================================================

;; create-loop : Task, WorktreeId, MaxAttempts -> ReviewLoop
;; Initialize a new review loop

;; step! : ReviewLoop, AgentConfig, Context, Worktree -> ReviewLoop
;; Execute one iteration of the loop (propose or review)

;; run-loop! : ReviewLoop, AgentConfig, Context, Worktree -> ReviewLoop
;; Run loop to completion (approved or exhausted)

;; approved? : ReviewLoop -> Boolean
;; Check if loop ended with approval

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const DEFAULT_MAX_ATTEMPTS 5)

;; =============================================================================
;; State Management
;; =============================================================================

(defn- now-ms []
  (System/currentTimeMillis))

(defn create-loop
  "Create a new review loop for a task"
  [task-id worktree-id {:keys [max-attempts] :or {max-attempts DEFAULT_MAX_ATTEMPTS}}]
  {:task-id task-id
   :worktree-id worktree-id
   :max-attempts max-attempts
   :attempts []
   :status :in-progress})

(defn approved?
  "Check if loop completed with approval"
  [loop-state]
  (= :approved (:status loop-state)))

(defn exhausted?
  "Check if loop exhausted max attempts"
  [loop-state]
  (= :exhausted (:status loop-state)))

(defn attempt-count
  "Get number of attempts so far"
  [loop-state]
  (count (:attempts loop-state)))

(defn last-feedback
  "Get feedback from most recent attempt"
  [loop-state]
  (-> loop-state :attempts last :feedback))

(defn can-continue?
  "Check if loop can continue (not done, not exhausted)"
  [loop-state]
  (and (= :in-progress (:status loop-state))
       (< (attempt-count loop-state) (:max-attempts loop-state))))

;; =============================================================================
;; Feedback Formatting
;; =============================================================================

(defn- format-feedback-for-proposer
  "Format review feedback as instructions for proposer to fix"
  [feedback]
  (let [{:keys [verdict comments violations suggested-fixes]} feedback
        sections (cond-> []
                   (seq violations)
                   (conj (str "Violations:\n"
                              (str/join "\n" (map #(str "- " %) violations))))

                   (seq comments)
                   (conj (str "Feedback:\n"
                              (str/join "\n" (map #(str "- " %) comments))))

                   (seq suggested-fixes)
                   (conj (str "Suggested fixes:\n"
                              (str/join "\n" (map #(str "- " %) suggested-fixes)))))]
    (if (seq sections)
      (str "Previous review feedback:\n\n"
           (str/join "\n\n" sections)
           "\n\nPlease address the above issues.")
      "Please review and improve the implementation.")))

;; =============================================================================
;; Step Execution
;; =============================================================================

(defn- run-proposer!
  "Execute proposer agent, commit changes"
  [agent-config task context worktree feedback]
  (let [;; Augment context with feedback if this is a retry
        augmented-context (if feedback
                            (assoc context :review_feedback
                                   (format-feedback-for-proposer feedback))
                            context)
        result (agent/propose! agent-config task augmented-context worktree)]
    (when (zero? (:exit result))
      ;; Commit changes in worktree
      (let [msg (if feedback
                  (format "fix: address review feedback for %s" (:id task))
                  (format "feat: implement %s" (:id task)))]
        (worktree/commit-in-worktree! worktree msg)))
    result))

(defn- run-reviewer!
  "Execute reviewer agent on current worktree state"
  [agent-config task context worktree]
  (agent/review! agent-config task context worktree))

(defn- create-attempt
  "Create an attempt record from review result"
  [attempt-number review-result worktree]
  {:attempt-number attempt-number
   :timestamp (now-ms)
   :feedback (or (:parsed review-result)
                 {:verdict :needs-changes
                  :comments ["Review failed to produce structured output"]})
   :patch-hash nil})  ; TODO: compute hash of current diff

(defn step!
  "Execute one iteration of propose->review.

   Returns updated ReviewLoop with new attempt recorded."
  [loop-state agent-config task context worktree]
  (when-not (can-continue? loop-state)
    (throw (ex-info "Cannot continue loop" {:loop loop-state})))

  (let [attempt-num (inc (attempt-count loop-state))
        prev-feedback (last-feedback loop-state)

        ;; Step 1: Proposer makes/fixes changes
        propose-result (run-proposer! agent-config task context worktree prev-feedback)

        _ (when-not (zero? (:exit propose-result))
            (throw (ex-info "Proposer failed"
                            {:exit (:exit propose-result)
                             :stderr (:stderr propose-result)})))

        ;; Step 2: Reviewer evaluates
        review-result (run-reviewer! agent-config task context worktree)

        ;; Record attempt
        attempt (create-attempt attempt-num review-result worktree)
        verdict (get-in attempt [:feedback :verdict])

        ;; Update loop state
        new-attempts (conj (:attempts loop-state) attempt)
        new-status (cond
                     (= verdict :approved) :approved
                     (>= attempt-num (:max-attempts loop-state)) :exhausted
                     :else :in-progress)]

    (assoc loop-state
           :attempts new-attempts
           :status new-status)))

;; =============================================================================
;; Full Loop Execution
;; =============================================================================

(defn run-loop!
  "Run the review loop to completion.

   Continues until:
     - Approved: reviewer accepts changes
     - Exhausted: max attempts reached
     - Aborted: unrecoverable error

   Returns final ReviewLoop state."
  [loop-state agent-config task context worktree]
  (loop [state loop-state]
    (if (can-continue? state)
      (let [new-state (try
                        (step! state agent-config task context worktree)
                        (catch Exception e
                          (assoc state
                                 :status :aborted
                                 :error (.getMessage e))))]
        (if (= :in-progress (:status new-state))
          (recur new-state)
          new-state))
      state)))

;; =============================================================================
;; Convenience API
;; =============================================================================

(defn review-task!
  "Complete review flow for a task: create loop, run to completion.

   Arguments:
     agent-config - AgentConfig for proposer/reviewer
     task         - Task to implement
     context      - Context map for prompts
     worktree     - Worktree to work in
     opts         - {:max-attempts N}

   Returns final ReviewLoop state"
  [agent-config task context worktree opts]
  (let [loop-state (create-loop (:id task) (:id worktree) opts)]
    (run-loop! loop-state agent-config task context worktree)))

(defn summarize-loop
  "Generate human-readable summary of review loop"
  [loop-state]
  (let [{:keys [task-id status attempts max-attempts]} loop-state
        attempt-summaries (map (fn [{:keys [attempt-number feedback]}]
                                 (format "  Attempt %d: %s"
                                         attempt-number
                                         (name (:verdict feedback :unknown))))
                               attempts)]
    (str (format "Task: %s\n" task-id)
         (format "Status: %s\n" (name status))
         (format "Attempts: %d/%d\n" (count attempts) max-attempts)
         (when (seq attempt-summaries)
           (str "History:\n" (str/join "\n" attempt-summaries))))))

;; =============================================================================
;; Logging / Persistence
;; =============================================================================

(defn loop->log-entry
  "Convert loop state to log entry format"
  [loop-state]
  {:task-id (:task-id loop-state)
   :status (case (:status loop-state)
             :approved :merged  ; will become merged after actual merge
             :exhausted :failed
             :aborted :failed
             :in-progress)
   :review-attempts (attempt-count loop-state)
   :final-verdict (get-in (last-feedback loop-state) [:verdict])
   :error (:error loop-state)})
