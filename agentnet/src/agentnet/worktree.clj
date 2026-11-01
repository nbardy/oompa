(ns agentnet.worktree
  "Git worktree management for isolated agent workspaces.

   Each agent gets its own worktree (directory + branch) so they can
   work in parallel without stepping on each other.

   Lifecycle:
     1. init-pool!     - Create N worktrees at startup
     2. acquire!       - Claim a worktree for a task
     3. (agent works)  - Agent modifies files, commits
     4. release!       - Mark worktree available, without discarding work
     5. cleanup-pool!  - Remove all worktrees at shutdown

   Design:
     - Worktrees live outside the project; .workers/ stores pool state
     - Each worktree has its own unique work/oompa-* branch
     - State tracked in .workers/state.json
     - Existing, dirty, busy and unmerged work is preserved"
  (:require [agentnet.schema :as schema]
            [agentnet.cambium :as cambium]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Function Specs (Hickey-style: data in, data out)
;; =============================================================================

;; init-pool! : OrchestratorConfig -> WorktreePool
;; Creates N external Cambium workspaces, returns pool state

;; acquire! : WorktreePool, TaskId -> [WorktreePool, Worktree | nil]
;; Claims first available worktree for task, returns updated pool + worktree

;; release! : WorktreePool, WorktreeId, {:reset? bool} -> WorktreePool
;; Releases worktree back to pool without discarding work

;; sync-to-main! : Worktree -> Worktree
;; Rebases worktree branch onto main, returns updated worktree

;; status : WorktreeId -> Worktree
;; Gets current state of worktree

;; cleanup-pool! : WorktreePool -> nil
;; Retires safe workspaces and retains their branches

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const WORKERS_DIR ".workers")
(def ^:const STATE_FILE ".workers/state.json")
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

;; =============================================================================
;; State Persistence
;; =============================================================================

(defn- now-ms []
  (System/currentTimeMillis))

(defn- ensure-workers-dir! []
  (.mkdirs (io/file WORKERS_DIR)))

(defn- save-state! [pool]
  (ensure-workers-dir!)
  (spit STATE_FILE (str (json/generate-string pool {:pretty true}) "\n"))
  pool)

(defn- load-state []
  (let [f (io/file STATE_FILE)]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

;; =============================================================================
;; Worktree CRUD
;; =============================================================================

(defn- create-worktree!
  "Create a unique Cambium workspace, never replacing earlier work."
  [worktree-id]
  (merge (cambium/create! (cambium/project-root) worktree-id BRANCH_PREFIX)
         {:id worktree-id :status :available :current-task nil
          :created-at (now-ms) :last-used nil}))

(defn- remove-worktree! [worktree]
  (cambium/remove! (or (:project-root worktree) (cambium/project-root)) worktree))

(defn- reset-worktree!
  "Refresh a released workspace without resetting or discarding any work."
  [worktree]
  (let [path (:path worktree)
        main-branch (current-branch)
        status (git-in path "status" "--porcelain")
        integrated (git "merge-base" "--is-ancestor" (:branch worktree) main-branch)
        safe? (and (zero? (:exit status)) (str/blank? (:out status))
                   (zero? (:exit integrated)))
        synced (when safe? (git-in path "merge" "--ff-only" main-branch))]
    (assoc worktree :current-task nil :last-used (now-ms)
           :status (cond
                     (not (zero? (:exit status))) :stale
                     (not (str/blank? (:out status))) :dirty
                     (and safe? (zero? (:exit synced))) :available
                     :else :stale))))

(defn- worktree-dirty?
  "Unreadable workspaces must never be handed to another agent."
  [{:keys [path]}]
  (let [{:keys [exit out]} (git-in path "status" "--porcelain")]
    (or (not (zero? exit)) (not (str/blank? out)))))

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

      (not (zero? (:exit (git "merge-base" "--is-ancestor" (:branch worktree) "HEAD"))))
      (assoc worktree :status :stale)

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
    (save-state! (into pool (remove #(some #{(:id %)} needed-ids) existing)))))

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
                    (refresh-worktree-status (assoc worktree :current-task nil)))
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
  "Remove only safe inactive workspaces; retain state for every preserved lane."
  [pool]
  (let [retained (filterv (fn [workspace]
                           (not (:removed? (remove-worktree! workspace)))) pool)]
    (if (seq retained)
      (save-state! retained)
      (when (.exists (io/file STATE_FILE))
        (io/delete-file STATE_FILE))))
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
          (release! pool' (:id worktree) {:reset? false})
          (throw e)))
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
