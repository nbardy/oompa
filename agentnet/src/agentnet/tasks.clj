(ns agentnet.tasks
  "Folder-based task management.

   Tasks live in tasks/{pending,current,complete}/*.edn

   Workers:
   - Claim tasks: mv pending/foo.edn → current/foo.edn
   - Complete tasks: mv current/foo.edn → complete/foo.edn
   - Create tasks: write new .edn to pending/

   Task format:
   {:id \"task-001\"
    :summary \"Add authentication\"
    :description \"Implement JWT auth...\"
    :files [\"src/auth.py\"]
    :acceptance [\"Login works\" \"Tests pass\"]}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Paths
;; =============================================================================

(def ^:const TASKS_ROOT "tasks")
(def ^:const PENDING_DIR (str TASKS_ROOT "/pending"))
(def ^:const CURRENT_DIR (str TASKS_ROOT "/current"))
(def ^:const COMPLETE_DIR (str TASKS_ROOT "/complete"))

(defn ensure-dirs!
  "Create task directories if they don't exist"
  []
  (doseq [dir [PENDING_DIR CURRENT_DIR COMPLETE_DIR]]
    (.mkdirs (io/file dir))))

;; =============================================================================
;; Task I/O
;; =============================================================================

(defn- read-task-file
  "Read a single task .edn file"
  [^java.io.File f]
  (when (.exists f)
    (try
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (let [task (edn/read {:eof nil} r)]
          (assoc task :_file (.getPath f))))
      (catch Exception e
        (println (format "[warn] Failed to read task %s: %s" (.getName f) (.getMessage e)))
        nil))))

(defn- list-task-files
  "List all .edn files in a directory"
  [dir]
  (let [d (io/file dir)]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (sort-by #(.getName %))))))

(defn list-pending
  "List all pending tasks"
  []
  (->> (list-task-files PENDING_DIR)
       (keep read-task-file)
       vec))

(defn list-current
  "List all in-progress tasks"
  []
  (->> (list-task-files CURRENT_DIR)
       (keep read-task-file)
       vec))

(defn list-complete
  "List all completed tasks"
  []
  (->> (list-task-files COMPLETE_DIR)
       (keep read-task-file)
       vec))

(defn list-all
  "List all tasks with their status"
  []
  (concat
    (map #(assoc % :status :pending) (list-pending))
    (map #(assoc % :status :current) (list-current))
    (map #(assoc % :status :complete) (list-complete))))

;; =============================================================================
;; Task Operations
;; =============================================================================

(defn- move-task!
  "Move a task file from one dir to another"
  [task from-dir to-dir]
  (let [filename (-> (:_file task) io/file .getName)
        from-path (io/file from-dir filename)
        to-path (io/file to-dir filename)]
    (when (.exists from-path)
      (.renameTo from-path to-path)
      (assoc task :_file (.getPath to-path)))))

(defn claim-task!
  "Claim a pending task (mv pending → current). Returns task or nil."
  [task]
  (move-task! task PENDING_DIR CURRENT_DIR))

(defn complete-task!
  "Mark a task complete (mv current → complete). Returns task or nil."
  [task]
  (move-task! task CURRENT_DIR COMPLETE_DIR))


(defn unclaim-task!
  "Return a task to pending (mv current → pending). Returns task or nil."
  [task]
  (move-task! task CURRENT_DIR PENDING_DIR))

(defn claim-next!
  "Claim the next available pending task. Returns task or nil."
  []
  (when-let [task (first (list-pending))]
    (claim-task! task)))

;; =============================================================================
;; Task Creation
;; =============================================================================

(defn- generate-task-id
  "Generate a unique task ID"
  []
  (format "task-%d" (System/currentTimeMillis)))

(defn- task->filename
  "Convert task to filename"
  [task]
  (let [id (or (:id task) (generate-task-id))
        safe-id (str/replace id #"[^a-zA-Z0-9-_]" "-")]
    (str safe-id ".edn")))

(defn create-task!
  "Create a new task in pending/. Returns the task with :_file set."
  [{:keys [id summary] :as task}]
  (ensure-dirs!)
  (let [task-id (or id (generate-task-id))
        task (assoc task :id task-id)
        filename (task->filename task)
        path (io/file PENDING_DIR filename)]
    (spit path (pr-str task))
    (assoc task :_file (.getPath path))))

(defn create-tasks!
  "Create multiple tasks. Returns list of created tasks."
  [tasks]
  (mapv create-task! tasks))

;; =============================================================================
;; Status Checks
;; =============================================================================

(defn pending-count [] (count (list-task-files PENDING_DIR)))
(defn current-count [] (count (list-task-files CURRENT_DIR)))
(defn complete-count [] (count (list-task-files COMPLETE_DIR)))

(defn current-task-ids
  "Return a set of task IDs currently in current/."
  []
  (->> (list-task-files CURRENT_DIR)
       (keep read-task-file)
       (map :id)
       set))

(defn recycle-tasks!
  "Move specific tasks from current/ back to pending/ by ID.
   Returns vector of recycled task IDs."
  [task-ids]
  (let [current (list-current)
        to-recycle (filter #(task-ids (:id %)) current)]
    (doseq [task to-recycle]
      (unclaim-task! task))
    (mapv :id to-recycle)))

(defn all-complete?
  "True if no pending or current tasks"
  []
  (and (zero? (pending-count))
       (zero? (current-count))))

(defn has-pending?
  "True if there are pending tasks"
  []
  (pos? (pending-count)))

(defn status-summary
  "Return status counts"
  []
  {:pending (pending-count)
   :current (current-count)
   :complete (complete-count)})

;; =============================================================================
;; Migration from old format
;; =============================================================================

(defn migrate-from-tasks-edn!
  "Migrate from config/tasks.edn to folder structure"
  []
  (let [old-file (io/file "config/tasks.edn")]
    (when (.exists old-file)
      (println "Migrating from config/tasks.edn...")
      (ensure-dirs!)
      (let [tasks (with-open [r (java.io.PushbackReader. (io/reader old-file))]
                    (edn/read {:eof nil} r))]
        (doseq [task tasks]
          (create-task! task))
        (println (format "Migrated %d tasks to tasks/pending/" (count tasks)))))))
