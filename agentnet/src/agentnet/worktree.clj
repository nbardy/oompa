(ns agentnet.worktree
  "Git worktree management for isolated agent workspaces.

   Each agent gets its own worktree (directory + branch) so they can
   work in parallel without stepping on each other.

   Lifecycle:
     1. init-pool!     - Create N worktrees at startup
     2. acquire!       - Claim a worktree for a task
     3. (agent works)  - Agent modifies files, commits
     4. release!       - Mark worktree available, optionally reset
     5. cleanup-pool!  - Remove all worktrees at shutdown

   Design:
     - Worktrees live in .workers/ directory
     - Each worktree has its own branch: work/<worktree-id>
     - State tracked in .workers/state.edn
     - All git operations are idempotent"
  (:require [agentnet.schema :as schema]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; =============================================================================
;; Function Specs (Hickey-style: data in, data out)
;; =============================================================================

;; init-pool! : OrchestratorConfig -> WorktreePool
;; Creates N worktrees in .workers/ directory, returns pool state

;; acquire! : WorktreePool, TaskId -> [WorktreePool, Worktree | nil]
;; Claims first available worktree for task, returns updated pool + worktree

;; release! : WorktreePool, WorktreeId, {:reset? bool} -> WorktreePool
;; Releases worktree back to pool, optionally resets to main

;; sync-to-main! : Worktree -> Worktree
;; Rebases worktree branch onto main, returns updated worktree

;; status : WorktreeId -> Worktree
;; Gets current state of worktree

;; cleanup-pool! : WorktreePool -> nil
;; Removes all worktrees and their branches

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const WORKERS_DIR ".workers")
(def ^:const STATE_FILE ".workers/state.edn")
(def ^:const BRANCH_PREFIX "work/")

;; =============================================================================
;; Git Helpers (pure where possible)
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

(defn- git-in
  "Run git command in specific directory"
  [dir & args]
  (apply git "-C" dir args))

(defn- git-in!
  "Run git command in specific directory, throw on failure"
  [dir & args]
  (apply git! "-C" dir args))

(defn- current-branch
  "Get current branch name"
  ([] (git! "rev-parse" "--abbrev-ref" "HEAD"))
  ([dir] (git-in! dir "rev-parse" "--abbrev-ref" "HEAD")))

(defn- current-sha
  "Get current commit SHA"
  ([] (git! "rev-parse" "HEAD"))
  ([dir] (git-in! dir "rev-parse" "HEAD")))

(defn- branch-exists?
  "Check if branch exists"
  [branch]
  (zero? (:exit (git "rev-parse" "--verify" branch))))

(defn- worktree-exists?
  "Check if worktree path exists"
  [path]
  (let [{:keys [out]} (git "worktree" "list" "--porcelain")]
    (str/includes? out path)))

;; =============================================================================
;; State Persistence
;; =============================================================================

(defn- now-ms []
  (System/currentTimeMillis))

(defn- ensure-workers-dir! []
  (.mkdirs (io/file WORKERS_DIR)))

(defn- save-state! [pool]
  (ensure-workers-dir!)
  (spit STATE_FILE (pr-str pool))
  pool)

(defn- load-state []
  (let [f (io/file STATE_FILE)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; =============================================================================
;; Worktree CRUD
;; =============================================================================

(defn- worktree-path [worktree-id]
  (str WORKERS_DIR "/" worktree-id))

(defn- worktree-branch [worktree-id]
  (str BRANCH_PREFIX worktree-id))

(defn- create-worktree!
  "Create a single worktree with its branch"
  [worktree-id]
  (let [path (worktree-path worktree-id)
        branch (worktree-branch worktree-id)]
    (ensure-workers-dir!)
    ;; Remove stale worktree if exists
    (when (worktree-exists? path)
      (git! "worktree" "remove" "--force" path))
    ;; Remove stale branch if exists
    (when (branch-exists? branch)
      (git! "branch" "-D" branch))
    ;; Create fresh worktree with new branch from HEAD
    (git! "worktree" "add" "-b" branch path "HEAD")
    {:id worktree-id
     :path path
     :branch branch
     :status :available
     :current-task nil
     :created-at (now-ms)
     :last-used nil}))

(defn- remove-worktree!
  "Remove a single worktree and its branch"
  [worktree]
  (let [{:keys [path branch]} worktree]
    (when (worktree-exists? path)
      (git "worktree" "remove" "--force" path))
    (when (branch-exists? branch)
      (git "branch" "-D" branch))
    nil))

(defn- reset-worktree!
  "Reset worktree to match main branch"
  [worktree]
  (let [{:keys [path branch]} worktree
        main-branch (current-branch)]
    ;; Fetch latest main
    (git-in! path "fetch" "." (str main-branch ":" main-branch))
    ;; Hard reset to main
    (git-in! path "reset" "--hard" main-branch)
    ;; Clean untracked files
    (git-in! path "clean" "-fd")
    (assoc worktree
           :status :available
           :current-task nil
           :last-used (now-ms))))

(defn- worktree-dirty?
  "Check if worktree has uncommitted changes"
  [worktree]
  (let [{:keys [path]} worktree
        {:keys [out]} (git-in path "status" "--porcelain")]
    (not (str/blank? out))))

(defn- refresh-worktree-status
  "Update worktree status based on git state"
  [worktree]
  (let [{:keys [path]} worktree]
    (cond
      (not (.exists (io/file path)))
      (assoc worktree :status :stale)

      (worktree-dirty? worktree)
      (assoc worktree :status :dirty)

      (:current-task worktree)
      (assoc worktree :status :busy)

      :else
      (assoc worktree :status :available))))

;; =============================================================================
;; Pool Management
;; =============================================================================

(defn init-pool!
  "Create a pool of N worktrees. Idempotent - reuses existing if clean."
  [{:keys [worker-count worktree-root] :as config}]
  (schema/assert-valid schema/valid-orchestrator-config? config "OrchestratorConfig")
  (ensure-workers-dir!)
  (let [existing (or (load-state) [])
        existing-ids (set (map :id existing))
        needed-ids (map #(str "worker-" %) (range worker-count))
        ;; Create missing worktrees
        pool (mapv (fn [id]
                     (if (contains? existing-ids id)
                       (-> (first (filter #(= (:id %) id) existing))
                           refresh-worktree-status)
                       (create-worktree! id)))
                   needed-ids)]
    (save-state! pool)))

(defn acquire!
  "Claim an available worktree for a task. Returns [pool worktree] or [pool nil]."
  [pool task-id]
  (schema/assert-valid schema/non-blank-string? task-id "TaskId")
  (if-let [idx (first (keep-indexed
                        (fn [i wt]
                          (when (= :available (:status wt)) i))
                        pool))]
    (let [worktree (-> (get pool idx)
                       (assoc :status :busy
                              :current-task task-id
                              :last-used (now-ms)))
          new-pool (assoc pool idx worktree)]
      (save-state! new-pool)
      [new-pool worktree])
    [pool nil]))

(defn release!
  "Release a worktree back to the pool."
  [pool worktree-id {:keys [reset?] :or {reset? true}}]
  (if-let [idx (first (keep-indexed
                        (fn [i wt]
                          (when (= (:id wt) worktree-id) i))
                        pool))]
    (let [worktree (get pool idx)
          updated (if reset?
                    (reset-worktree! worktree)
                    (assoc worktree
                           :status :available
                           :current-task nil))
          new-pool (assoc pool idx updated)]
      (save-state! new-pool))
    pool))

(defn sync-to-main!
  "Rebase worktree branch onto current main. Returns updated worktree."
  [worktree]
  (let [{:keys [path branch]} worktree
        main (current-branch)]
    ;; Fetch main into worktree
    (git-in! path "fetch" "origin" main)
    ;; Rebase onto main
    (let [{:keys [exit err]} (git-in path "rebase" (str "origin/" main))]
      (if (zero? exit)
        (assoc worktree :status :available)
        ;; Abort failed rebase
        (do
          (git-in path "rebase" "--abort")
          (throw (ex-info "Rebase failed" {:worktree worktree :error err})))))))

(defn status
  "Get current status of a worktree by ID"
  [pool worktree-id]
  (when-let [worktree (first (filter #(= (:id %) worktree-id) pool))]
    (refresh-worktree-status worktree)))

(defn cleanup-pool!
  "Remove all worktrees and clean up state"
  [pool]
  (doseq [worktree pool]
    (remove-worktree! worktree))
  (when (.exists (io/file STATE_FILE))
    (io/delete-file STATE_FILE))
  nil)

(defn list-worktrees
  "List all worktrees with current status"
  [pool]
  (mapv refresh-worktree-status pool))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn with-worktree
  "Execute function with an acquired worktree, auto-release on completion.

   Usage:
     (with-worktree pool task-id
       (fn [worktree]
         (do-work-in (:path worktree))))"
  [pool task-id f]
  (let [[pool' worktree] (acquire! pool task-id)]
    (if worktree
      (try
        (let [result (f worktree)]
          [(release! pool' (:id worktree) {:reset? false}) result])
        (catch Exception e
          [(release! pool' (:id worktree) {:reset? true}) nil]))
      (throw (ex-info "No available worktrees" {:pool pool :task-id task-id})))))

(defn commit-in-worktree!
  "Create a commit in the worktree"
  [worktree message]
  (let [{:keys [path]} worktree]
    (git-in! path "add" "-A")
    (let [{:keys [exit]} (git-in path "diff" "--cached" "--quiet")]
      (when-not (zero? exit)  ; there are staged changes
        (git-in! path "commit" "-m" message)))))

(defn diff-in-worktree
  "Get the diff of uncommitted changes in worktree"
  [worktree]
  (let [{:keys [path]} worktree]
    (:out (git-in path "diff"))))

(defn log-in-worktree
  "Get commit log for worktree branch (since divergence from main)"
  [worktree]
  (let [{:keys [path branch]} worktree
        main (current-branch)]
    (:out (git-in path "log" "--oneline" (str main ".." branch)))))
