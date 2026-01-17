(ns agentnet.merge
  "Git merge operations for integrating approved changes.

   After a task passes review, its worktree branch needs to be merged
   into main. This module handles:
     - Fast-forward merges (ideal case)
     - Merge commits (divergent histories)
     - Conflict detection and resolution
     - Rollback on failure

   Design:
     - Merges happen in main repo, not worktrees
     - Conflicts can be auto-resolved or bounced back
     - Each merge is atomic (succeed or rollback)
     - Merge results are logged for audit"
  (:require [agentnet.schema :as schema]
            [agentnet.worktree :as worktree]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Function Specs
;; =============================================================================

;; merge-branch! : GitBranch, MergeStrategy, Options -> MergeResult
;; Merge a branch into current branch (main)

;; detect-conflicts : GitBranch -> [FilePath]
;; Check what files would conflict if merged

;; resolve-conflicts! : ConflictResolution -> Boolean
;; Attempt to resolve merge conflicts

;; abort-merge! : -> nil
;; Abort an in-progress merge

;; =============================================================================
;; Git Helpers
;; =============================================================================

(defn- git
  "Run git command, return {:exit :out :err}"
  [& args]
  (let [cmd (into ["git"] args)
        {:keys [exit out err]} (process/sh cmd {:out :string :err :string})]
    {:exit exit
     :out (str/trim (or out ""))
     :err (str/trim (or err ""))}))

(defn- git!
  "Run git command, throw on failure"
  [& args]
  (let [{:keys [exit out err]} (apply git args)]
    (if (zero? exit)
      out
      (throw (ex-info (str "git failed: " err)
                      {:command args :exit exit :err err})))))

(defn- current-branch []
  (git! "rev-parse" "--abbrev-ref" "HEAD"))

(defn- current-sha []
  (git! "rev-parse" "HEAD"))

(defn- branch-sha [branch]
  (:out (git "rev-parse" branch)))

(defn- merge-in-progress? []
  (.exists (io/file ".git/MERGE_HEAD")))

(defn- stash-changes! []
  (let [{:keys [exit]} (git "stash" "push" "-m" "agentnet-auto-stash")]
    (zero? exit)))

(defn- pop-stash! []
  (git "stash" "pop"))

;; =============================================================================
;; Conflict Detection
;; =============================================================================

(defn detect-conflicts
  "Check what files would conflict if branch were merged.
   Returns vector of file paths, empty if no conflicts."
  [source-branch]
  (let [;; Dry-run merge to detect conflicts
        {:keys [exit out err]} (git "merge" "--no-commit" "--no-ff" source-branch)]
    (if (zero? exit)
      ;; No conflicts, abort the merge we started
      (do (git "merge" "--abort")
          [])
      ;; Parse conflict files from output
      (do
        (git "merge" "--abort")
        (->> (str/split-lines (str out "\n" err))
             (filter #(re-find #"CONFLICT|Merge conflict" %))
             (map #(second (re-find #"(?:in|Merge conflict in)\s+(.+)" %)))
             (remove nil?)
             vec)))))

(defn can-fast-forward?
  "Check if source branch can be fast-forwarded into current branch"
  [source-branch]
  (let [current (current-sha)
        merge-base (:out (git "merge-base" (current-branch) source-branch))]
    (= current merge-base)))

;; =============================================================================
;; Merge Strategies
;; =============================================================================

(defmulti execute-merge
  "Execute merge with specified strategy"
  (fn [source-branch strategy _opts] strategy))

(defmethod execute-merge :fast-forward
  [source-branch _ _opts]
  (let [{:keys [exit err]} (git "merge" "--ff-only" source-branch)]
    (if (zero? exit)
      {:status :merged
       :source-branch source-branch
       :target-branch (current-branch)
       :commit-sha (current-sha)}
      {:status :failed
       :source-branch source-branch
       :target-branch (current-branch)
       :error (str "Fast-forward not possible: " err)})))

(defmethod execute-merge :no-ff
  [source-branch _ {:keys [message]}]
  (let [msg (or message (str "Merge branch '" source-branch "'"))
        {:keys [exit err]} (git "merge" "--no-ff" "-m" msg source-branch)]
    (if (zero? exit)
      {:status :merged
       :source-branch source-branch
       :target-branch (current-branch)
       :commit-sha (current-sha)}
      {:status :conflict
       :source-branch source-branch
       :target-branch (current-branch)
       :conflicts (detect-conflicts source-branch)
       :error err})))

(defmethod execute-merge :squash
  [source-branch _ {:keys [message]}]
  (let [{:keys [exit err]} (git "merge" "--squash" source-branch)]
    (if (zero? exit)
      ;; Squash stages but doesn't commit
      (let [msg (or message (str "Squash merge: " source-branch))
            {:keys [exit]} (git "commit" "-m" msg)]
        (if (zero? exit)
          {:status :merged
           :source-branch source-branch
           :target-branch (current-branch)
           :commit-sha (current-sha)}
          {:status :failed
           :source-branch source-branch
           :target-branch (current-branch)
           :error "Squash commit failed"}))
      {:status :conflict
       :source-branch source-branch
       :target-branch (current-branch)
       :error err})))

(defmethod execute-merge :rebase
  [source-branch _ _opts]
  ;; Rebase source onto current, then fast-forward
  (let [current (current-branch)
        {:keys [exit err]} (git "rebase" current source-branch)]
    (if (zero? exit)
      ;; Switch back and fast-forward
      (do
        (git! "checkout" current)
        (execute-merge source-branch :fast-forward {}))
      (do
        (git "rebase" "--abort")
        {:status :conflict
         :source-branch source-branch
         :target-branch current
         :error (str "Rebase failed: " err)}))))

(defmethod execute-merge :default
  [source-branch strategy _opts]
  (throw (ex-info (str "Unknown merge strategy: " strategy)
                  {:strategy strategy :source-branch source-branch})))

;; =============================================================================
;; Conflict Resolution
;; =============================================================================

(defn resolve-conflicts!
  "Attempt to resolve merge conflicts.

   Strategies:
     :ours   - Accept current branch version
     :theirs - Accept incoming branch version
     :manual - Leave for human intervention
     :abort  - Abort the merge entirely"
  [resolution]
  (when-not (merge-in-progress?)
    (throw (ex-info "No merge in progress" {})))

  (case resolution
    :ours
    (do
      (git! "checkout" "--ours" ".")
      (git! "add" "-A")
      (let [{:keys [exit]} (git "commit" "--no-edit")]
        (zero? exit)))

    :theirs
    (do
      (git! "checkout" "--theirs" ".")
      (git! "add" "-A")
      (let [{:keys [exit]} (git "commit" "--no-edit")]
        (zero? exit)))

    :abort
    (do
      (git! "merge" "--abort")
      false)

    :manual
    false  ; Leave conflicts for human

    (throw (ex-info (str "Unknown resolution: " resolution)
                    {:resolution resolution}))))

(defn abort-merge!
  "Abort an in-progress merge"
  []
  (when (merge-in-progress?)
    (git! "merge" "--abort")))

;; =============================================================================
;; Main API
;; =============================================================================

(defn merge-branch!
  "Merge a branch into current branch.

   Arguments:
     source-branch - Branch to merge from
     strategy      - :fast-forward, :no-ff, :squash, :rebase
     opts          - {:message str, :conflict-resolution keyword, :dry-run bool}

   Returns MergeResult"
  [source-branch strategy opts]
  (schema/assert-valid schema/git-branch? source-branch "source-branch")
  (schema/assert-valid schema/merge-strategy? strategy "strategy")

  (if (:dry-run opts)
    (let [conflicts (detect-conflicts source-branch)]
      {:status (if (empty? conflicts) :would-merge :would-conflict)
       :source-branch source-branch
       :target-branch (current-branch)
       :conflicts conflicts})
    ;; else: actually merge
    (let [result (execute-merge source-branch strategy opts)]
      ;; Handle conflicts if resolution strategy specified
      (if (and (= :conflict (:status result))
               (:conflict-resolution opts))
        (if (resolve-conflicts! (:conflict-resolution opts))
          (assoc result :status :merged :commit-sha (current-sha))
          result)
        result))))

(defn merge-worktree!
  "Merge a worktree's branch into main.

   Arguments:
     worktree - Worktree map with :branch
     opts     - Merge options

   Returns MergeResult"
  [worktree opts]
  (let [{:keys [branch]} worktree
        strategy (or (:strategy opts) :no-ff)
        message (or (:message opts)
                    (format "Merge %s: %s"
                            (:id worktree)
                            (or (:current-task worktree) "task")))]
    (merge-branch! branch strategy (assoc opts :message message))))

;; =============================================================================
;; Safe Merge with Rollback
;; =============================================================================

(defn safe-merge!
  "Merge with automatic rollback on failure.

   Creates a backup ref before merge, restores on failure."
  [source-branch strategy opts]
  (let [backup-ref (str "refs/agentnet/backup/" (System/currentTimeMillis))
        original-sha (current-sha)]
    ;; Create backup
    (git! "update-ref" backup-ref original-sha)

    (try
      (let [result (merge-branch! source-branch strategy opts)]
        (if (#{:merged} (:status result))
          (do
            ;; Clean up backup on success
            (git "update-ref" "-d" backup-ref)
            result)
          (do
            ;; Rollback on failure
            (git! "reset" "--hard" original-sha)
            (git "update-ref" "-d" backup-ref)
            result)))
      (catch Exception e
        ;; Rollback on exception
        (git! "reset" "--hard" original-sha)
        (git "update-ref" "-d" backup-ref)
        (throw e)))))

;; =============================================================================
;; Batch Merge
;; =============================================================================

(defn merge-all!
  "Merge multiple worktrees in sequence.

   Stops on first conflict unless :continue-on-conflict is true.
   Returns vector of MergeResults."
  [worktrees opts]
  (loop [remaining worktrees
         results []]
    (if-let [wt (first remaining)]
      (let [result (safe-merge! (:branch wt) (or (:strategy opts) :no-ff) opts)]
        (if (and (= :conflict (:status result))
                 (not (:continue-on-conflict opts)))
          (conj results result)  ; Stop on conflict
          (recur (rest remaining) (conj results result))))
      results)))

;; =============================================================================
;; Cleanup
;; =============================================================================

(defn delete-merged-branch!
  "Delete a branch that has been merged"
  [branch]
  (let [{:keys [exit]} (git "branch" "-d" branch)]
    (zero? exit)))

(defn cleanup-after-merge!
  "Clean up worktree and branch after successful merge"
  [worktree pool]
  (let [{:keys [id branch]} worktree]
    ;; Release worktree (resets to main)
    (worktree/release! pool id {:reset? true})
    ;; Delete the merged branch
    (delete-merged-branch! branch)))
