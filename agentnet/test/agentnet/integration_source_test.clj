(ns agentnet.integration-source-test
  (:require [agentnet.integration-source :as source]
            [agentnet.task-execution :as execution]
            [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.runs :as runs]
            [agentnet.cambium :as cambium]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(defn git! [root & args]
  (let [result (process/sh (into ["git"] args) {:dir root :out :string :err :string})]
    (when-not (zero? (:exit result)) (throw (ex-info "Fixture git failed" result)))
    (str/trim (:out result))))

(defn commit! [root file text]
  (spit (io/file root file) text)
  (git! root "add" file) (git! root "commit" "-m" (str "fixture " file)))

(defn fixture []
  (let [parent (str (fs/create-temp-dir {:prefix "oompa-integration-"}))
        root (str (io/file parent "root"))]
    (.mkdirs (io/file root))
    (git! root "init" "-b" "main")
    (git! root "config" "user.name" "Fixture")
    (git! root "config" "user.email" "fixture@example.invalid")
    (spit (io/file root ".gitignore") "runs/\n")
    (spit (io/file root "one.txt") "broken") (spit (io/file root "two.txt") "broken")
    (spit (io/file root "oracle.py")
          "import json,os,pathlib\ntask=os.environ['OOMPA_TASK_ID']\nok=pathlib.Path(task+'.txt').read_text()=='repaired'\nprint(json.dumps({'schema':'oompa.task-validation/v1','task_id':task,'source_commit':os.environ['OOMPA_SOURCE_COMMIT'],'accepted':ok}))\nraise SystemExit(0 if ok else 1)\n")
    (git! root "add" ".") (git! root "commit" "-m" "fixture")
    (let [base (git! root "rev-parse" "HEAD")
          spaces (mapv (fn [id]
                         (let [path (str (io/file parent id))]
                           (git! root "worktree" "add" "-b" id path base)
                           {:path path :name id :dir id :branch id :source_commit base})) ["one" "two"])]
      {:parent parent :root root :base base :spaces spaces})))

(deftest two-held-chunks-from-one-base-accept-serially-with-same-session-and-no-owner-merge
  (let [{:keys [parent root base spaces]} (fixture)
        previous-root (System/getProperty "user.dir")
        gate {:command ["python3" "oracle.py"] :timeout_seconds 2
              :source_files {"oracle.py" (execution/file-sha256 (io/file root "oracle.py"))}}
        deadline (+ (execution/now-ms) 10000)
        store (atom (into {} (for [id ["one" "two"]]
                              [id {:id id :status "open" :paused false :revision 2 :title id :doneCriteria "real gate"}])))
        initial {:cycle 3 :attempt 0 :accepted_count 0 :deadline_ms deadline
                 :held_executions (into {} (map (fn [ws]
                                                 [(:name ws) {:task_id (:name ws) :claim_revision 1 :held_task_revision 1
                                                              :workspace ws :session_id (str (:name ws) "-original")
                                                              :attempt 1 :attempt_history [{:attempt 1 :output "held"}]
                                                              :phase "implementing" :held_reason "canonical-task-blocked"
                                                              :validator_digest (execution/validator-digest gate)}]) spaces))}
        snapshots (atom []) models (atom []) reviews (atom []) accepted (atom [])
        shutdown (var-get #'worker/shutdown-requested?)]
    (try
      (doseq [ws spaces] (commit! (:path ws) (str (:name ws) ".txt") "repaired"))
      (System/setProperty "user.dir" root) (reset! shutdown false)
      (with-redefs [tasks/buddies? (constantly true)
                    tasks/list-all (fn [] (mapv @store ["one" "two"]))
                    tasks/list-pending (fn [] (filterv #(= "open" (:status %)) (mapv @store ["one" "two"])))
                    tasks/get-task (fn [id] (get @store id))
                    tasks/claim-task! (fn [task]
                                        (let [claimed (-> task (assoc :status "in_progress") (update :revision inc))]
                                          (swap! store assoc (:id task) claimed) claimed))
                    runs/read-worker-state (constantly {:execution initial})
                    runs/write-worker-state! (fn [_ _ state] (swap! snapshots conj state))
                    runs/write-cycle-log! (fn [& _]) runs/write-review-log! (fn [& _])
                    cambium/list-workspaces (constantly spaces)
                    cambium/claim-existing! (fn [_ ws] ws)
                    cambium/remove! (fn [& _] {:removed? false})
                    worker/run-agent!
                    (fn [_ path _ session resume? & _]
                      (swap! models conj {:session session :resume resume? :path path})
                      (when (= "two-original" session)
                        (is (= "repaired" (slurp (io/file path "one.txt"))))
                        (is (= 2 (count (str/split (git! path "show" "-s" "--format=%P") #" ")))))
                      {:exit 0 :merge? true :session-id session :output "ready"})
                    worker/run-reviewer!
                    (fn [_ path _]
                      (swap! reviews conj (git! path "rev-parse" "HEAD"))
                      {:verdict :approved :exit 0 :output "VERDICT: APPROVED"})
                    tasks/request-acceptance!
                    (fn [task workspace commit]
                      (let [receipt (execution/validate! (:path workspace) task gate deadline)]
                        (is (execution/accepted? receipt))
                        (is (= commit (:source_commit receipt)))
                        (git! root "merge" "--ff-only" commit)
                        (swap! accepted conj {:task (:id task) :commit commit})
                        (swap! store update (:id task) #(-> % (assoc :status "done") (update :revision inc)))
                        {:kind "accepted" :validation receipt}))]
        (let [result (worker/run-selected-worker! {:id "w0" :swarm-id "serial" :resume-swarm-id "held"
                                                   :max-cycles 10 :max-resumes 5 :reviewers [{:harness :claude}]
                                                   :task-execution {:wall_time_seconds 20 :attempt_timeout_seconds 2
                                                                    :validators {"one" gate "two" gate}}})
              final (:execution (last @snapshots))]
          (is (= :completed (:status result)))
          (is (= ["one" "two"] (mapv :task @accepted)))
          (is (= ["one-original" "two-original"] (mapv :session @models)))
          (is (every? :resume @models)) (is (= 2 (count @reviews)))
          (is (= deadline (:deadline_ms final)))
          (is (= "repaired" (slurp (io/file root "one.txt"))))
          (is (= "repaired" (slurp (io/file root "two.txt"))))
          (let [two-state (last (keep #(when (seq (get-in % [:execution :source_merge_history])) (:execution %)) @snapshots))]
            (is (= 2 (:attempt two-state)))
            (is (= 2 (count (:attempt_history two-state))))
            (is (= 1 (count (:source_merge_history two-state)))))))
      (finally (reset! shutdown false) (System/setProperty "user.dir" previous-root) (fs/delete-tree parent)))))

(deftest successful-refresh-invalidates-previous-ready-and-validation-receipts
  (let [{:keys [parent root spaces]} (fixture) [one two] spaces deadline (+ (execution/now-ms) 10000)]
    (try
      (commit! (:path one) "one.txt" "integration")
      (commit! (:path two) "two.txt" "candidate")
      (git! root "merge" "--ff-only" (git! (:path one) "rev-parse" "HEAD"))
      (with-redefs [cambium/list-workspaces (constantly spaces)]
        (let [prior (git! (:path two) "rev-parse" "HEAD")
              state {:workspace two :attempt 9 :session_id "retained" :deadline_ms deadline
                     :phase "validating" :candidate_pin {:source_commit prior} :validation {:accepted true}}
              refreshed (source/refresh! root state deadline)]
          (is (= "integration-source-merged" (get-in refreshed [:feedback :reason])))
          (is (= "implementing" (:phase refreshed)))
          (is (nil? (:candidate_pin refreshed))) (is (nil? (:validation refreshed)))
          (is (= [9 "retained" deadline] ((juxt :attempt :session_id :deadline_ms) refreshed)))
          (is (= "integration" (slurp (io/file (:path two) "one.txt"))))
          (is (= "candidate" (slurp (io/file (:path two) "two.txt"))))
          (is (not= prior (git! (:path two) "rev-parse" "HEAD")))
          (is (= refreshed (source/refresh! root refreshed deadline)))))
      (finally (fs/delete-tree parent)))))

(deftest refresh-invalidates-old-receipts-and-preserves-conflicts-and-foreign-source
  (let [{:keys [parent root spaces]} (fixture) [one two] spaces deadline (+ (execution/now-ms) 10000)
        state {:workspace two :attempt 9 :session_id "retained" :deadline_ms deadline
               :phase "validating" :candidate_pin {:source_commit "old"} :validation {:accepted true}}]
    (try
      (commit! (:path one) "one.txt" "integration")
      (git! root "merge" "--ff-only" (git! (:path one) "rev-parse" "HEAD"))
      (commit! (:path two) "one.txt" "candidate")
      (binding [cambium/*deadline-ms* deadline]
        (with-redefs [cambium/list-workspaces (constantly spaces)]
          (let [before (git! (:path two) "rev-parse" "HEAD")
                conflict (source/refresh! root state deadline)]
            (is (= "integration-merge-conflict" (get-in conflict [:feedback :reason])))
            (is (= "implementing" (:phase conflict)))
            (is (nil? (:candidate_pin conflict))) (is (nil? (:validation conflict)))
            (is (= 9 (:attempt conflict))) (is (= "retained" (:session_id conflict)))
            (is (= before (git! (:path two) "rev-parse" "HEAD")))
            (is (not (str/blank? (git! (:path two) "ls-files" "--unmerged"))))
            (is (= (git! root "rev-parse" "HEAD") (git! (:path two) "rev-parse" "MERGE_HEAD"))))))
      ;; Independent candidate: missing source custody refuses without a merge.
      (let [third-path (str (io/file parent "third"))]
        (git! root "worktree" "add" "-b" "third" third-path (:source_commit two))
        (let [third {:path third-path :name "third" :dir "third"}]
          (with-redefs [cambium/list-workspaces (constantly [third])]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"foreign integration lineage"
                                 (source/refresh! root (assoc state :workspace third) deadline)))
            (is (= (:source_commit two) (git! third-path "rev-parse" "HEAD"))))))
      (finally (fs/delete-tree parent)))))

(let [{:keys [fail error]} (run-tests 'agentnet.integration-source-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
