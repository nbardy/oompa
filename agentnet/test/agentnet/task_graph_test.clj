(ns agentnet.task-graph-test
  (:require [agentnet.task-graph :as graph]
            [agentnet.task-execution :as execution]
            [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.buddy-tasks :as buddy]
            [agentnet.runs :as runs]
            [agentnet.cambium :as cambium]
            [agentnet.cli :as cli]
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
(defn save! [root file value]
  (spit (io/file root file) value) (git! root "add" file) (git! root "commit" "-m" (str "fixture " file)))
(defn fixture []
  (let [parent (str (fs/create-temp-dir {:prefix "oompa-graph-"})) root (str (io/file parent "source"))]
    (.mkdirs (io/file root))
    (git! root "init" "-b" "main") (git! root "config" "user.name" "Fixture")
    (git! root "config" "user.email" "fixture@example.invalid")
    (spit (io/file root ".gitignore") "runs/\nconfig.json\n")
    (spit (io/file root "worker.md") "historical prompt")
    (spit (io/file root "consumer.py")
          "import json,os,pathlib\nt=os.environ['OOMPA_TASK_ID']\nokay=pathlib.Path(t+'.txt').read_text()=='repaired'\nprint(json.dumps({'schema':'oompa.task-validation/v1','task_id':t,'source_commit':os.environ['OOMPA_SOURCE_COMMIT'],'accepted':okay,'scope':'SOFTWARE fixture only'}))\nraise SystemExit(0 if okay else 1)\n")
    (doseq [id ["child1" "child2" "child3" "parent" "review"]] (spit (io/file root (str id ".txt")) "broken"))
    (git! root "add" ".") (git! root "commit" "-m" "fixture")
    {:parent parent :root root}))
(defn gate [root]
  {:command ["python3" "consumer.py"] :timeout_seconds 2
   :source_files {"consumer.py" (execution/file-sha256 (io/file root "consumer.py"))}})
(defn task [id status]
  {:id id :title id :workspaceId "workspace" :ownerId "buddy" :status status :paused false :revision 1
   :doneCriteria "Real source-bound SOFTWARE fixture; never physical/account acceptance"})

(deftest preflight-admits-independent-ready-work-and-classifies-source-repairs-without-running-consumers
  (let [{:keys [parent root]} (fixture) good (gate root)
        bad (assoc good :source_files {"consumer.py" (apply str (repeat 64 "0"))})
        config {:validators {"ready" good "stale" bad "done" {:source_files {"worker.md" "historical"}}
                             "unselected" {:source_files {"not-selected.py" "never-inspected"}}}
                :task_readiness {"force" {:role "observed" :blocker_class "external" :reason_code "native-evidence-required"}}}
        selected [(task "ready" "open") (task "missing" "blocked") (task "stale" "blocked")
                  (task "done" "done") (task "force" "blocked")]]
    (try
      (save! root "worker.md" "current prompt; accepted historical pin deliberately differs")
      (let [report (graph/audit! root config selected {"done" ["historical-removed-prerequisite"]} {})
            items (into {} (map (juxt :task_id identity) (:items report)))]
        (is (= "ready" (:state report)))
        (is (= #{"ready"} (graph/ready-ids report)))
        (is (= [] (get-in items ["done" :source_files])))
        (is (= "accepted" (get-in items ["done" :state])))
        (is (= "externally-blocked" (get-in items ["force" :state])))
        (is (= #{"consumer-unconfigured" "canonical-hold" "validator-source-mismatch"}
               (set (map :code (:actions report)))))
        (is (= report (graph/audit! root config selected {"done" ["historical-removed-prerequisite"]} {})))
        (is (= "running" (:state (graph/audit! root config [(task "ready" "in_progress")] {} {}
                                                {:active-task-ids ["ready"]}))))
        (is (= "needs-reconciliation" (:state (graph/audit! root config [(task "ready" "in_progress")] {} {}))))
        (is (= 0 (:exit (process/sh ["git" "status" "--porcelain"] {:dir root})))))
      (finally (fs/delete-tree parent)))))

(deftest observed-owner-led-acceptance-never-requires-selected-execution-custody
  (let [{:keys [parent root]} (fixture)
        config {:validators {}
                :task_readiness {"final" {:role "observed" :blocker_class "acceptance" :reason_code "owner-acceptance"}
                                 "users" {:role "observed" :blocker_class "external" :reason_code "participants-required"}}}]
    (try
      (doseq [dependencies [{} {"final" ["users"]}]]
        (let [report (graph/audit! root config [(task "final" "in_progress") (task "users" "blocked")]
                                   dependencies {})]
          (is (= "externally-blocked" (:state report)))
          (is (empty? (:actions report)))
          (is (not-any? #(= "execution-custody-unverified" (:code %)) (mapcat :issues (:items report))))))
      (finally (fs/delete-tree parent)))))

(deftest typed-graph-detects-cycle-without-hiding-ready-prerequisites
  (let [{:keys [parent root]} (fixture) config {:validators {"repair" (gate root)}}]
    (try
      (let [report (graph/audit! root config [(task "one" "open") (task "two" "open") (task "repair" "open")]
                                 {"one" ["two"] "two" ["one"]} {})]
        (is (= "ready" (:state report))) (is (= #{"repair"} (graph/ready-ids report)))
        (is (= 2 (count (filter #(= "dependency-cycle" (:code %)) (:actions report))))))
      (finally (fs/delete-tree parent)))))

(deftest explicit-owner-window-preserves-history-and-cannot-be-forged-by-a-config-amendment
  (let [old (- (execution/now-ms) 1000) expiry (+ (execution/now-ms) 10000)
        saved {:deadline_ms old :attempt 9 :session_id "original" :workspace {:path "retained"}}
        window {:authorization_id "new-owner-launch" :prior_deadline_ms old :expires_at_ms expiry}]
    (is (= old (:deadline_ms (execution/resume-budget saved {} expiry))))
    (with-redefs [execution/owner-window-environment (constantly {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching host authority"
                           (execution/resume-budget saved {:owner_window window} expiry))))
    (with-redefs [execution/owner-window-environment (constantly (dissoc window :prior_deadline_ms))]
      (let [new (execution/resume-budget saved {:owner_window window} expiry)]
        (is (= expiry (:deadline_ms new))) (is (= (dissoc saved :deadline_ms) (dissoc new :deadline_ms :owner_window :deadline_history)))
        (is (= [{:deadline_ms old :owner_window nil}] (:deadline_history new)))
        (is (= new (execution/resume-budget new {:owner_window window} expiry))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (execution/resume-budget saved {:owner_window (assoc window :prior_deadline_ms (dec old))} expiry))))))

(deftest three-children-exhaust-actionably-then-reviewed-canonical-prerequisite-enables-real-consumer
  (let [{:keys [parent root]} (fixture) previous-root (System/getProperty "user.dir") good (gate root)
        store (atom (into {} (map #(vector % (task % "open")) ["child1" "child2" "child3" "parent"])))
        children ["child1" "child2" "child3"]
        original-backend {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids (conj children "parent")}
        original-config {:wall_time_seconds 30 :attempt_timeout_seconds 2
                         :validators (into {} (map #(vector % (assoc-in good [:source_files "worker.md"]
                                                                            (execution/file-sha256 (io/file root "worker.md")))) children))}
        worker-config {:id "w0" :swarm-id "before" :max-cycles 10 :max-resumes 5 :prompts []
                       :reviewers [{:harness :claude}] :task-execution original-config}
        model-calls (atom []) accepts (atom []) spaces (atom []) snapshots (atom {}) writes (atom 0)
        shutdown (var-get #'worker/shutdown-requested?)
        transport (fn [tool args]
                    (case tool
                      "tasks" {:task (get @store (get-in args [:action :taskId]))}
                      "task_write" (let [{:keys [taskId baseRevision changes]} (:write args) old (get @store taskId)]
                                     (is (= baseRevision (:revision old)))
                                     (let [new (merge old changes {:revision (inc baseRevision)})]
                                       (swap! store assoc taskId new) new))
                      "oompa" (let [{:keys [taskId sourceCommit workspacePath]} (:action args)
                                    receipt (execution/validate! workspacePath (get @store taskId) good (+ (execution/now-ms) 5000))]
                                (is (execution/accepted? receipt)) (is (= sourceCommit (:source_commit receipt)))
                                (git! root "merge" "--ff-only" sourceCommit)
                                (swap! store update taskId #(-> % (assoc :status "done") (update :revision inc)))
                                (swap! accepts conj taskId)
                                {:kind "accepted" :task (get @store taskId) :validation receipt})))]
    (try
      (System/setProperty "user.dir" root) (reset! shutdown false) (tasks/configure! original-backend)
      (binding [buddy/*transport* transport]
        (with-redefs [runs/read-worker-state (fn [swarm _] (get @snapshots swarm))
                      runs/write-worker-state! (fn [swarm _ value] (swap! writes inc) (swap! snapshots assoc swarm value))
                      runs/write-cycle-log! (fn [& _]) runs/write-review-log! (fn [& _])
                      cambium/list-workspaces (fn [_] @spaces) cambium/claim-existing! (fn [_ ws] ws)
                      cambium/remove! (fn [& _] {:removed? false})
                      worker/create-iteration-worktree!
                      (fn [_ swarm _ cycle]
                        (let [name (str swarm "-" cycle) path (str (io/file parent name)) base (git! root "rev-parse" "HEAD")
                              ws {:name name :dir name :branch name :path path :source_commit base}]
                          (git! root "worktree" "add" "-b" name path base) (swap! spaces conj ws) ws))
                      worker/run-agent! (fn [_ path _ session _ & _]
                                          (let [id (first (filter #(and (= "in_progress" (:status (get @store %)))
                                                                        (not (some #{%} @model-calls)))
                                                                  (concat children ["review" "parent"])))]
                                            (swap! model-calls conj id) (save! path (str id ".txt") "repaired")
                                            {:exit 0 :merge? true :session-id (or session (str id "-session")) :output "source ready"}))
                      worker/run-reviewer! (fn [& _] {:verdict :approved :exit 0 :output "VERDICT: APPROVED"})]
          (spit (io/file root "config.json") (json/generate-string {:task_backend original-backend :task_execution original-config}))
          (let [preflight (json/parse-string (with-out-str (cli/cmd-audit {:config-file (str (io/file root "config.json"))} [])) true)]
            (is (= "oompa.task-graph/v1" (:schema preflight)))
            (is (= "ready" (:state preflight)))
            (is (= (set children) (graph/ready-ids preflight)))
            (is (empty? @model-calls)) (is (zero? @writes)))
          (let [result (worker/run-selected-worker! worker-config)
                saved (:execution (get @snapshots "before")) fingerprint (get-in saved [:queue_audit :fingerprint])]
            (is (= :needs-reconciliation (:status result))) (is (= children @accepts))
            (is (= 3 (:accepted_count saved))) (is (= children @model-calls))
            (is (= "consumer-unconfigured" (get-in saved [:queue_audit :actions 0 :code])))
            ;; The unchanged graph produces no model/CAS or polling writes;
            ;; one terminal receipt is mandatory in the new run's journal.
            (reset! writes 0) (tasks/configure! original-backend)
            (let [again (worker/run-selected-worker! (assoc worker-config :swarm-id "unchanged" :resume-swarm-id "before"))]
              (is (= :needs-reconciliation (:status again))) (is (false? (:queue_audit_changed again)))
              (is (= 1 @writes)) (is (= children @model-calls))
              (is (= fingerprint (get-in @snapshots ["unchanged" :execution :queue_audit :fingerprint]))))
            ;; Source prompt revision belongs to the new consumer; done child
            ;; pins stay historical and are never reread at the new HEAD.
            (save! root "worker.md" "reviewed next consumer prompt")
            (swap! store assoc "review" (task "review" "open"))
            (swap! store update "parent" #(-> % (assoc :status "open") (update :revision inc)))
            (tasks/configure! (assoc original-backend :task_ids (into children ["review" "parent"])
                                     :dependencies {"parent" ["review"]}))
            (let [amended (assoc worker-config :swarm-id "after" :resume-swarm-id "before"
                                 :task-execution (assoc original-config :validators (merge (:validators original-config)
                                                                                          {"review" good "parent" good})))
                  result (worker/run-selected-worker! amended) after (:execution (get @snapshots "after"))]
              (is (= :completed (:status result))) (is (= (into children ["review" "parent"]) @accepts))
              (is (= (into children ["review" "parent"]) @model-calls))
              (is (= (:deadline_ms saved) (:deadline_ms after))) (is (= 5 (:accepted_count after)))
              (is (not= fingerprint (get-in after [:queue_audit :fingerprint])))))))
      (finally (tasks/configure! nil) (reset! shutdown false) (System/setProperty "user.dir" previous-root)
               (fs/delete-tree parent)))))

(deftest expensive-rejection-checkpoints-one-real-consumer-and-never-retries-unchanged-claim
  (let [{:keys [parent root]} (fixture) previous-root (System/getProperty "user.dir")
        validator (assoc (gate root) :reconcile_on_rejection true)
        deadline (+ (execution/now-ms) 20000)
        stored (atom (task "parent" "open")) spaces (atom []) models (atom 0) consumers (atom 0)
        snapshots (atom {})
        config {:id "w0" :swarm-id "rejected" :max-cycles 5 :max-resumes 100 :prompts []
                :reviewers [{:harness :claude}]
                :task-execution {:wall_time_seconds 20 :deadline_ms deadline :attempt_timeout_seconds 2
                                 :validators {"parent" validator}}}
        shutdown (var-get #'worker/shutdown-requested?)]
    (try
      (System/setProperty "user.dir" root) (reset! shutdown false)
      (is (= (execution/validator-digest validator)
             (execution/validator-digest (dissoc validator :reconcile_on_rejection))))
      (is (not= (execution/validator-digest validator)
                (execution/validator-digest (assoc validator :command ["python3" "different.py"]))))
      (with-redefs [tasks/buddies? (constantly true) tasks/selection-dependencies (constantly {})
                    tasks/list-all (fn [] [@stored]) tasks/list-pending (fn [] (if (= "open" (:status @stored)) [@stored] []))
                    tasks/get-task (fn [_] @stored)
                    tasks/claim-task! (fn [_] (swap! stored #(-> % (assoc :status "in_progress") (update :revision inc))))
                    runs/read-worker-state (fn [swarm _] (get @snapshots swarm))
                    runs/write-worker-state! (fn [swarm _ state] (swap! snapshots assoc swarm state))
                    runs/write-cycle-log! (fn [& _]) runs/write-review-log! (fn [& _])
                    cambium/list-workspaces (fn [_] @spaces) cambium/claim-existing! (fn [_ ws] ws)
                    cambium/remove! (fn [& _] {:removed? false})
                    worker/create-iteration-worktree!
                    (fn [& _]
                      (let [path (str (io/file parent "candidate")) name "candidate" base (git! root "rev-parse" "HEAD")
                            ws {:path path :name name :dir name :branch name :source_commit base}]
                        (git! root "worktree" "add" "-b" name path base) (swap! spaces conj ws) ws))
                    worker/run-agent!
                    (fn [_ path _ session _ & _]
                      (swap! models inc) (save! path "parent.txt" "still-broken")
                      {:exit 0 :merge? true :session-id (or session "original-session") :output "request real consumer"})
                    worker/run-reviewer! (fn [& _] {:verdict :approved :exit 0 :output "VERDICT: APPROVED"})
                    tasks/request-acceptance!
                    (fn [task workspace commit]
                      (swap! consumers inc)
                      (let [receipt (execution/validate! (:path workspace) task validator deadline)]
                        (is (= "rejected" (:status receipt))) (is (= commit (:source_commit receipt)))
                        {:kind "rejected" :validation receipt}))]
        (let [result (worker/run-selected-worker! config)
              saved (:execution (get @snapshots "rejected")) held (get-in saved [:held_executions "parent"])]
          (is (= :needs-reconciliation (:status result)))
          (is (= 1 @models)) (is (= 1 @consumers))
          (is (= "consumer-rejected" (:held_reason held)))
          (is (= "original-session" (:session_id held))) (is (= 1 (:attempt held)))
          (is (= 1 (count (:attempt_history held))))
          (is (= "rejected" (get-in held [:feedback :status])))
          (is (= "in_progress" (:status @stored)))
          (is (.isDirectory (io/file (get-in held [:workspace :path]))))
          (let [again (worker/run-selected-worker! (assoc config :swarm-id "same-rejection" :resume-swarm-id "rejected"))]
            (is (= :needs-reconciliation (:status again))) (is (false? (:queue_audit_changed again)))
            (is (= 1 @models)) (is (= 1 @consumers))
            (is (= deadline (get-in @snapshots ["same-rejection" :execution :deadline_ms]))))))
      (finally (reset! shutdown false) (System/setProperty "user.dir" previous-root) (fs/delete-tree parent)))))

(let [{:keys [fail error]} (run-tests 'agentnet.task-graph-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
