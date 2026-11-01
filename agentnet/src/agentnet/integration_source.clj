(ns agentnet.integration-source
  "Advance retained candidates with accepted integration commits, never rebase."
  (:require [agentnet.bounded-process :as bounded]
            [agentnet.cambium :as cambium]
            [agentnet.task-execution :as execution]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- run-git [cwd deadline & args]
  (let [remaining (execution/remaining-ms deadline)]
    (if (pos? remaining)
      (bounded/run! (into ["git"] args) {:dir cwd} (min 30000 remaining))
      {:exit 124 :out "" :err "Integration source deadline exhausted" :timed-out? true})))

(defn- ancestor? [root before after deadline]
  (let [result (run-git root deadline "merge-base" "--is-ancestor" before after)]
    (case (:exit result)
      0 true
      1 false
      (throw (ex-info "Cannot verify candidate integration ancestry" {:result result})))))

(defn- invalidate [state reason details]
  (-> state
      (dissoc :candidate_pin :validation)
      (assoc :phase "implementing" :feedback (merge {:reason reason} details))))

(defn refresh!
  "Called only under the selected writer lock. Merge current accepted root into
   a clean registered candidate rooted on this same integration lineage. Keep
   conflicts and model history; no cleanup/reset/rebase or acceptance here."
  [root state deadline]
  (let [{:keys [path source_commit] :as workspace} (:workspace state)
        integration (execution/git! root deadline "rev-parse" "HEAD")
        candidate (execution/git! path deadline "rev-parse" "HEAD")
        merging (run-git path deadline "rev-parse" "--verify" "-q" "MERGE_HEAD")]
    (cond
      (zero? (:exit merging))
      (invalidate state "integration-merge-conflict"
                  {:integration_commit (str/trim (:out merging))
                   :nextAction "Resolve and commit the retained merge in this workspace; then request fresh review."})

      (ancestor? root integration candidate deadline)
      (if (and (= "validating" (:phase state))
               (not= candidate (get-in state [:candidate_pin :source_commit])))
        (invalidate state "candidate-source-changed" {:candidate_commit candidate})
        state)

      :else
      (let [canonical #(.getCanonicalPath (io/file %))
            registered (some #(when (= (canonical path) (canonical (:path %))) %)
                             (cambium/list-workspaces root))]
        (when-not (and registered source_commit
                       (= (or (:name workspace) (:dir workspace)) (:name registered))
                       (= (:branch workspace) (execution/git! path deadline "symbolic-ref" "--short" "HEAD"))
                       (= (execution/git! root deadline "rev-parse" "--path-format=absolute" "--git-common-dir")
                          (execution/git! path deadline "rev-parse" "--path-format=absolute" "--git-common-dir"))
                       (ancestor? root source_commit integration deadline)
                       (ancestor? root source_commit candidate deadline))
          (throw (ex-info "Candidate cannot merge an unregistered or foreign integration lineage"
                          {:reason :integration-lineage-unverified :workspace workspace})))
        (if-not (str/blank? (execution/git! path deadline "status" "--porcelain" "--untracked-files=all"))
          (invalidate state "integration-source-needs-commit"
                      {:integration_commit integration
                       :nextAction "Commit current work or resolve the existing merge; the framework will then merge accepted integration source."})
          (let [result (run-git path deadline "merge" "--no-edit" integration)
                merged (execution/git! path deadline "rev-parse" "HEAD")
                next-state (-> state
                               (invalidate (if (zero? (:exit result)) "integration-source-merged" "integration-merge-conflict")
                                           {:integration_commit integration :previous_candidate_commit candidate
                                            :candidate_commit merged
                                            :nextAction (if (zero? (:exit result))
                                                          "Inspect the combined source and rerun affected controls before fresh review/validation."
                                                          "Resolve and commit the retained merge; then rerun controls and request fresh review.")})
                               (update :source_merge_history (fnil conj [])
                                       {:integration_commit integration :previous_candidate_commit candidate
                                        :candidate_commit merged :exit (:exit result)}))]
            next-state))))))
