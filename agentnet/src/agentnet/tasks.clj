(ns agentnet.tasks
  "Task backend boundary. Filesystem is the upstream default; Buddy is explicit and fail closed."
  (:require [agentnet.filesystem-tasks :as filesystem]
            [agentnet.buddy-tasks :as buddy]))

;; Backend boundary: the original implementations remain the upstream filesystem default.
;; Buddy mode never enters filesystem I/O or migration, including directory creation.
(defonce ^:private backend (atom :filesystem))
(defn buddies? [] (= :buddies @backend))
(defn backend? [] @backend)
(defn with-deadline [deadline f]
  (binding [buddy/*deadline-ms* deadline] (f)))
(defn configure! [config]
  (let [kind (keyword (or (:type config) "filesystem"))]
    (case kind
      :filesystem (reset! backend :filesystem)
      :buddies (do (buddy/configure! config) (reset! backend :buddies))
      (throw (ex-info "Unknown task backend; expected filesystem or buddies" {:type kind}))))
  nil)

(defn ensure-dirs! [] (when-not (buddies?) (filesystem/ensure-dirs!)))
(defn migrate-from-tasks-json! [] (when-not (buddies?) (filesystem/migrate-from-tasks-json!)))
(defn list-pending [] (if (buddies?) (buddy/list-pending) (filesystem/list-pending)))
(defn list-current [] (if (buddies?) (buddy/list-current) (filesystem/list-current)))
(defn list-complete [] (if (buddies?) (buddy/list-complete) (filesystem/list-complete)))
(defn list-all [] (if (buddies?) (buddy/list-all) (filesystem/list-all)))
(defn selection-dependencies [] (if (buddies?) (buddy/selection-dependencies) {}))
(defn get-task [id]
  (if (buddies?) (buddy/get-task id) (first (filter #(= id (:id %)) (list-all)))))
(defn claim-task! [task] (if (buddies?) (buddy/claim-task! task) (filesystem/claim-task! task)))
(defn restore-claim! [id revision]
  (if (buddies?) (buddy/restore-claim! id revision)
      (throw (ex-info "Persisted selected-task execution requires the Buddy backend" {}))))
(defn request-acceptance! [task workspace source-commit]
  (if (buddies?) (buddy/request-acceptance! task workspace source-commit)
      (throw (ex-info "Executable acceptance requires the run-owned Buddy host" {}))))
(defn complete-task! [task] (if (buddies?) (buddy/complete-task! task) (filesystem/complete-task! task)))
(defn unclaim-task! [task] (if (buddies?) (buddy/unclaim-task! task) (filesystem/unclaim-task! task)))
(defn block-task! [task result]
  (if (buddies?) (buddy/block-task! task result)
      (throw (ex-info "Selected-task holds require the Buddy backend" {}))))
(defn release-abandoned! [task next-action]
  (if (buddies?) (buddy/release-abandoned! task next-action)
      (throw (ex-info "Selected-task releases require the Buddy backend" {}))))
(defn claim-next! []
  (if (buddies?) (some buddy/claim-task! (list-pending)) (filesystem/claim-next!)))
(defn claim-by-id!
  ([id] (claim-by-id! id nil))
  ([id opts] (if (buddies?) (buddy/claim-by-id! id opts) (filesystem/claim-by-id! id opts))))
(defn complete-by-ids! [ids]
  (if (buddies?)
    (->> ids (keep #(buddy/complete-task! (buddy/get-task %))) (mapv :id))
    (filesystem/complete-by-ids! ids)))
(defn create-task! [task] (if (buddies?) (buddy/create-task! task) (filesystem/create-task! task)))
(defn pending-count [] (if (buddies?) (count (list-pending)) (filesystem/pending-count)))
(defn current-count [] (if (buddies?) (count (list-current)) (filesystem/current-count)))
(defn complete-count [] (if (buddies?) (count (list-complete)) (filesystem/complete-count)))
(defn current-task-ids []
  (if (buddies?) (set (map :id (list-current))) (filesystem/current-task-ids)))
(defn recycle-tasks! [ids]
  (if (buddies?)
    (->> ids (keep #(buddy/unclaim-task! (buddy/get-task %))) (mapv :id))
    (filesystem/recycle-tasks! ids)))
(defn all-complete? [] (if (buddies?) (buddy/all-complete?) (filesystem/all-complete?)))
(defn claim-by-ids!
  ([ids] (claim-by-ids! ids nil))
  ([ids opts] (mapv #(claim-by-id! % opts) ids)))
(defn create-tasks! [tasks] (mapv create-task! tasks))
(defn recycle-all-current! [] (recycle-tasks! (current-task-ids)))
(defn has-pending? [] (pos? (pending-count)))
(defn status-summary []
  {:pending (pending-count) :current (current-count) :complete (complete-count)})
