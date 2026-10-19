(ns agentnet.task-execution-test
  (:require [agentnet.task-execution :as execution]
            [agentnet.bounded-process :as bounded]
            [agentnet.worker :as worker]
            [agentnet.tasks :as tasks]
            [agentnet.buddy-tasks :as buddy]
            [agentnet.harness :as harness]
            [agentnet.runs :as runs]
            [agentnet.cambium :as cambium]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(defn git [root & args]
  (let [result (process/sh (into ["git"] args) {:dir (str root) :out :string :err :string})]
    (when-not (zero? (:exit result)) (throw (ex-info "Fixture git failed" result)))
    (str/trim (:out result))))

(def oracle
  (str "import json, os, pathlib\n"
       "task = os.environ['OOMPA_TASK_ID']\n"
       "actual = pathlib.Path(task + '.txt').read_text().strip()\n"
       "accepted = actual == 'repaired'\n"
       "print(json.dumps({'schema':'oompa.task-validation/v1', 'task_id':task, "
       "'source_commit':os.environ['OOMPA_SOURCE_COMMIT'], 'accepted':accepted, 'actual':actual}))\n"
       "raise SystemExit(0 if accepted else 1)\n"))

(defn fixture-repo []
  (let [root (str (fs/create-temp-dir {:prefix "oompa-execution-"}))]
    (git root "init" "-b" "main")
    (git root "config" "user.name" "Oompa fixture")
    (git root "config" "user.email" "fixture@example.invalid")
    (spit (io/file root ".gitignore") "runs/\n")
    (spit (io/file root "oracle.py") oracle)
    (spit (io/file root "one.txt") "broken\n")
    (spit (io/file root "two.txt") "broken\n")
    (git root "add" ".")
    (git root "commit" "-m" "fixture")
    root))

(defn validator [root]
  {:command ["python3" "oracle.py"] :timeout_seconds 2
   :source_files {"oracle.py" (execution/file-sha256 (io/file root "oracle.py"))}})

(defn commit-file! [root file contents]
  (spit (io/file root file) contents)
  (git root "add" file)
  (git root "commit" "-m" (str "fixture edit " file)))

(deftest real-gate-rejects-broken-source-and-pins-oracle-and-commit
  (let [root (fixture-repo) task {:id "one" :revision 2}
        gate (validator root) deadline (+ (execution/now-ms) 10000)]
    (try
      (let [failed (execution/validate! root task gate deadline)]
        (is (= "rejected" (:status failed)))
        (is (= "broken" (get-in failed [:report :actual])))
        (is (false? (execution/accepted? failed))))
      (commit-file! root "one.txt" "repaired\n")
      (let [passed (execution/validate! root task gate deadline)]
        (is (execution/accepted? passed))
        (is (= (git root "rev-parse" "HEAD") (:source_commit passed))))
      (spit (io/file root "one.txt") "dirty\n")
      (is (= "dirty-source" (:reason (execution/validate! root task gate deadline))))
      (git root "checkout" "--" "one.txt")
      (commit-file! root "oracle.py" "print('forged green')\n")
      (let [tampered (execution/validate! root task gate deadline)]
        (is (= "blocked" (:status tampered)))
        (is (= "validator-pin-mismatch" (:reason tampered))))
      (finally (fs/delete-tree root)))))

(deftest process-timeout-kills-descendants-not-just-the-waiter
  (let [root (str (fs/create-temp-dir {:prefix "oompa-timeout-"}))
        marker (str (io/file root "escaped"))
        started (execution/now-ms)]
    (try
      (let [result (bounded/run! ["python3" "-c"
                                 (str "import subprocess,time; subprocess.Popen(['python3','-c',"
                                      (pr-str (str "import time,pathlib; time.sleep(.4); pathlib.Path(" (pr-str marker) ").write_text('escaped')"))
                                      "]); time.sleep(10)")]
                                {} 150)]
        (is (= 124 (:exit result)))
        (is (:timed-out? result))
        (is (< (- (execution/now-ms) started) 1000)))
      (Thread/sleep 500)
      (is (not (.exists (io/file marker))))
      (finally (fs/delete-tree root)))))

(defn task [id status]
  {:id id :workspaceId "workspace" :ownerId "buddy" :title id
   :doneCriteria "The real executable consumer accepts repaired source"
   :status status :paused false :revision 1})

(defn fixture-transport
  "A fake run-owned host with a REAL command oracle and REAL ff-only Git merge.
   It cannot accept on the implementation model's completion text."
  [root store calls gate]
  (fn [tool args]
    (swap! calls conj [tool args])
    (case tool
      "tasks" {:task (get @store (get-in args [:action :taskId]))}
      "task_write"
      (let [{:keys [taskId baseRevision changes]} (:write args)
            old (get @store taskId)]
        (when-not (= baseRevision (:revision old))
          (throw (ex-info "revision conflict" {:type :revision-conflict})))
        (when (= "done" (:status changes)) (throw (ex-info "worker cannot mark done" {})))
        (let [updated (merge old changes {:revision (inc baseRevision)})]
          (swap! store assoc taskId updated)
          updated))
      "oompa"
      (let [{:keys [taskId baseRevision sourceCommit workspacePath]} (:action args)
            old (get @store taskId)
            _ (when-not (= baseRevision (:revision old))
                (throw (ex-info "revision conflict" {:type :revision-conflict})))
            receipt (execution/validate! workspacePath old gate (+ (execution/now-ms) 5000))]
        (if (execution/accepted? receipt)
          (do
            (is (= sourceCommit (:source_commit receipt)))
            (is (= "" (git root "status" "--porcelain")))
            (git root "merge" "--ff-only" sourceCommit)
            (let [accepted (assoc old :status "done" :revision (inc baseRevision))]
              (swap! store assoc taskId accepted)
              {:kind "accepted" :task accepted :validation receipt}))
          {:kind (:status receipt) :task old :validation receipt})))))

(deftest selected-worker-repairs-same-session-then-next-task-and-does-not-complete-parent
  (let [root (fixture-repo)
        previous-root (System/getProperty "user.dir")
        store (atom {"one" (task "one" "open") "two" (task "two" "open")
                     "parent" (task "parent" "open") "a-no-oracle" (task "a-no-oracle" "open")})
        calls (atom []) model-calls (atom []) review-calls (atom []) retired (atom [])
        gate (validator root)
        config {:type "buddies" :workspace_id "workspace" :buddy_id "buddy"
                :task_ids ["parent" "one" "two" "a-no-oracle"] :dependencies {"two" ["one"]}}
        workspaces (atom [])]
    (try
      (System/setProperty "user.dir" root)
      (tasks/configure! config)
      (binding [buddy/*transport* (fixture-transport root store calls gate)]
        (with-redefs [worker/build-template-tokens (fn [& _] {})
                      worker/create-iteration-worktree!
                      (fn [_ _ _ cycle]
                        (let [path (str root "-candidate-" cycle)
                              branch (str "candidate-" cycle)]
                          (git root "worktree" "add" "-b" branch path)
                          (swap! workspaces conj path)
                          {:path path :dir branch :name branch :branch branch}))
                      cambium/remove! (fn [_ ws] (swap! retired conj (:path ws)) {:removed? false})
                      harness/run-command!
                      (fn [h opts]
                        (if (= :claude h)
                          (do (swap! review-calls conj opts)
                              {:exit 0 :err ""
                               :out (json/generate-string
                                      {:type "text.delta"
                                       :text (if (= 1 (count @review-calls))
                                               "The candidate still needs work.\nVERDICT: NEEDS_CHANGES"
                                               "VERDICT: APPROVED")})})
                        (let [n (inc (count @model-calls))
                              id (if (< n 4) "one" "two")
                              sid (if (< n 4) "session-one" "session-two")]
                          (swap! model-calls conj opts)
                          (when (> n 1) (commit-file! (:cwd opts) (str id ".txt")
                                                     (if (= n 2) "still-broken\n" "repaired\n")))
                          {:exit 0 :err ""
                           :out (str (json/generate-string {:type "session.started" :sessionId sid}) "\n"
                                     (json/generate-string {:type "text.delta" :text "COMPLETE_AND_READY_FOR_MERGE"}) "\n")})))]
          (let [result (worker/run-worker! {:id "w0" :swarm-id "fixture" :harness :codex :model "fixture"
                                          :max-resumes 4 :max-cycles 10 :prompts [] :can-plan false
                                          :reviewers [{:harness :claude :model "opus-review-fixture" :reasoning "high"}]
                                          :task-execution {:wall_time_seconds 4 :attempt_timeout_seconds 1
                                                           :validators {"one" gate "two" gate}}})
                saved (runs/read-worker-state "fixture" "w0")]
            (is (= 4 (count @model-calls)))
            (is (= 4 (count @review-calls)))
            (is (every? #(= "high" (:reasoning %)) @review-calls))
            (is (str/includes? (:prompt (first @review-calls)) "real executable consumer"))
            (is (= (:cwd (first @model-calls)) (:cwd (second @model-calls))))
            (is (= (:cwd (second @model-calls)) (:cwd (nth @model-calls 2))))
            (is (not= (:cwd (nth @model-calls 2)) (:cwd (nth @model-calls 3))))
            (is (= "session-one" (:session-id (second @model-calls))))
            (is (true? (:resume? (second @model-calls))))
            (is (str/includes? (:prompt (second @model-calls)) "reviewer-rejected"))
            (is (str/includes? (:prompt (nth @model-calls 2)) "still-broken"))
            (is (every? #(<= 1 (:timeout-ms %) 1000) @model-calls))
            (is (= ["done" "done" "open" "open"]
                   (mapv #(get-in @store [% :status]) ["one" "two" "parent" "a-no-oracle"])))
            (is (= :wall-time-exhausted (:status result)))
            (is (= 2 (:completed result)))
            (is (= "wall-time-exhausted" (:reason saved)))
            (is (= 3 (count (filter #(= "oompa" (first %)) @calls))))
            (is (not-any? #(= "done" (get-in % [1 :write :changes :status])) @calls))
            (is (= 2 (count @retired))))))
      (finally
        (tasks/configure! nil)
        (System/setProperty "user.dir" previous-root)
        (doseq [path @workspaces] (fs/delete-tree path))
        (fs/delete-tree root)))))

(deftest preparation-review-is-held-while-independent-validated-work-continues
  (let [root (fixture-repo) previous-root (System/getProperty "user.dir")
        store (atom {"prep" (task "prep" "open") "two" (task "two" "open")})
        calls (atom []) model-calls (atom []) workspaces (atom [])
        gate (validator root)]
    (try
      (System/setProperty "user.dir" root)
      (tasks/configure! {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids ["prep" "two"]})
      (binding [buddy/*transport* (fixture-transport root store calls gate)]
        (with-redefs [worker/build-template-tokens (fn [& _] {})
                      worker/create-iteration-worktree!
                      (fn [_ _ _ cycle]
                        (let [path (str root "-prepare-" cycle) branch (str "prepare-" cycle)]
                          (git root "worktree" "add" "-b" branch path)
                          (swap! workspaces conj path)
                          {:path path :dir branch :name branch :branch branch}))
                      cambium/remove! (fn [& _] {:removed? false})
                      harness/run-command!
                      (fn [h opts]
                        (if (= :claude h)
                          {:exit 0 :out "VERDICT: APPROVED" :err ""}
                          (do
                            (swap! model-calls conj opts)
                            (when (= 2 (count @model-calls))
                              (commit-file! (:cwd opts) "two.txt" "repaired\n"))
                            {:exit 0 :err ""
                             :out (str (json/generate-string {:type "session.started" :sessionId "preparation-session"}) "\n"
                                       (json/generate-string {:type "text.delta" :text "COMPLETE_AND_READY_FOR_MERGE"}))})))]
          (let [result (worker/run-worker! {:id "w0" :swarm-id "preparation" :harness :codex
                                          :max-resumes 3 :max-cycles 10 :prompts []
                                          :reviewers [{:harness :claude :model "opus-review" :reasoning "high"}]
                                          :task-execution {:wall_time_seconds 2 :attempt_timeout_seconds 1
                                                           :prepare_only_task_ids ["prep"] :validators {"two" gate}}})
                held (get-in (runs/read-worker-state "preparation" "w0") [:execution :held_executions :prep])]
            (is (= 2 (count @model-calls)))
            (is (= "review" (get-in @store ["prep" :status])))
            (is (= "done" (get-in @store ["two" :status])))
            (is (= "validator-unconfigured" (:held_reason held)))
            (is (= "preparation-session" (:session_id held)))
            (is (.isDirectory (io/file (get-in held [:workspace :path]))))
            (is (= :wall-time-exhausted (:status result))))))
      (finally
        (tasks/configure! nil)
        (System/setProperty "user.dir" previous-root)
        (doseq [path @workspaces] (fs/delete-tree path))
        (fs/delete-tree root)))))

(deftest interrupted-execution-resumes-exact-source-and-session-and-refuses-foreign-revision
  (let [root (fixture-repo) previous-root (System/getProperty "user.dir")
        store (atom {"one" (task "one" "open")}) calls (atom []) model-calls (atom [])
        workspace (atom nil) gate (validator root)
        backend {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids ["one"]}
        config {:id "w0" :swarm-id "before" :harness :codex :max-resumes 3 :max-cycles 1 :prompts []
                :reviewers [{:harness :claude :model "opus-review" :reasoning "high"}]
                :task-execution {:wall_time_seconds 10 :attempt_timeout_seconds 1 :validators {"one" gate}}}
        shutdown (var-get #'worker/shutdown-requested?)]
    (try
      (System/setProperty "user.dir" root)
      (reset! shutdown false)
      (tasks/configure! backend)
      (binding [buddy/*transport* (fixture-transport root store calls gate)]
        (with-redefs [worker/build-template-tokens (fn [& _] {})
                      worker/create-iteration-worktree!
                      (fn [& _]
                        (let [path (str root "-resume")]
                          (git root "worktree" "add" "-b" "resume-candidate" path)
                          (reset! workspace {:path path :dir "resume-candidate" :name "resume-candidate" :branch "resume-candidate"})))
                      cambium/list-workspaces (fn [_] [@workspace])
                      cambium/claim-existing! (fn [_ ws] ws)
                      cambium/remove! (fn [& _] {:removed? false})
                      harness/run-command!
                      (fn [h opts]
                        (if (= :claude h)
                          {:exit 0 :out "VERDICT: APPROVED" :err ""}
                          (let [n (inc (count @model-calls))]
                            (swap! model-calls conj opts)
                            (if (= n 1) (reset! shutdown true)
                                (commit-file! (:cwd opts) "one.txt" "repaired\n"))
                            {:exit 0 :err ""
                             :out (str (json/generate-string {:type "session.started" :sessionId "exact-session"}) "\n"
                                       (json/generate-string {:type "text.delta" :text (if (= n 1) "Still working" "COMPLETE_AND_READY_FOR_MERGE")}))})))]
          (is (= :interrupted (:status (worker/run-worker! config))))
          (reset! shutdown false)
          (tasks/configure! backend)
          (swap! store update-in ["one" :revision] inc)
          (is (thrown-with-msg? Exception #"canonical claim"
                                (worker/run-worker! (assoc config :swarm-id "refused" :resume-swarm-id "before"))))
          (is (= 1 (count @model-calls)))
          (swap! store update-in ["one" :revision] dec)
          (tasks/configure! backend)
          (let [result (worker/run-worker! (assoc config :swarm-id "after" :resume-swarm-id "before"))
                before (get-in (runs/read-worker-state "before" "w0") [:execution :deadline_ms])
                after (get-in (runs/read-worker-state "after" "w0") [:execution :deadline_ms])]
            (is (= :completed (:status result)))
            (is (= "done" (get-in @store ["one" :status])))
            (is (= 2 (count @model-calls)))
            (is (= (:cwd (first @model-calls)) (:cwd (second @model-calls))))
            (is (= "exact-session" (:session-id (second @model-calls))))
            (is (:resume? (second @model-calls)))
            (is (= before after)))))
      (finally
        (reset! shutdown false)
        (tasks/configure! nil)
        (System/setProperty "user.dir" previous-root)
        (when @workspace (fs/delete-tree (:path @workspace)))
        (fs/delete-tree root)))))

(deftest selected-stop-cannot-promote-a-cancelled-or-unaccepted-parent
  (with-redefs [tasks/list-all (fn [] [{:id "child" :status "done"} {:id "parent" :status "cancelled"}])]
    (is (= :awaiting-buddy-acceptance (#'worker/stopped-reason [{:status :completed}] 0 true)))
    (is (= :wall-time-exhausted (#'worker/stopped-reason [{:status :wall-time-exhausted}] nil true)))))


(def force-blocked-output
  (str "```json\n"
       (json/generate-string
         {:status "blocked" :candidateCommit "e4e63b23f"
          :reason "Required native force evidence cannot be produced under the unchanged CUDA, replay and funding holds."
          :requiredOwnerAction "Supply authorized CUDA target, replay/funding decision and complete force/Product consumer oracle."
          :taskComplete false :readyForMergeValidation false}
         {:pretty true})
       "\n```\nPlease retain this candidate pending the owner handoff."))

(deftest blocked-result-is-explicit-structured-hold-only
  (is (= "blocked" (:status (execution/blocked-result force-blocked-output "one"))))
  (doseq [output ["Still blocked; no CUDA/replay/funding or oracle."
                  "COMPLETE_AND_READY_FOR_MERGE"
                  "{\"status\":\"blocked\",\"reason\":\"CUDA\"}"
                  (json/generate-string {:status "accepted" :reason "done" :taskComplete false :readyForMergeValidation false})
                  (json/generate-string {:status "blocked" :reason "" :taskComplete false :readyForMergeValidation false})
                  (json/generate-string {:status "blocked" :reason "CUDA" :taskComplete true :readyForMergeValidation false})
                  (json/generate-string {:status "blocked" :reason "CUDA" :taskComplete false :readyForMergeValidation false :task_id "other"})]]
    (is (nil? (execution/blocked-result output "one")))))

(deftest blocked-return-holds-before-review-and-continues-independent-work-and-survives-restart
  ;; Replay the actual turn-five fenced object, plus races where canonical
  ;; state changes during a model call that even claims merge readiness.
  (doseq [mode [:structured :canonical-blocked :canonical-paused :canonical-review :canonical-revision :stopped-active]]
    (testing (name mode)
      (let [root (fixture-repo) previous-root (System/getProperty "user.dir")
            store (atom {"one" (task "one" "open") "two" (task "two" "open")})
            calls (atom []) model-calls (atom []) review-calls (atom []) workspaces (atom []) reopening? (atom false)
            gate (validator root) shutdown (var-get #'worker/shutdown-requested?)
            write-state! runs/write-worker-state!
            backend {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids ["one" "two"]}
            config {:id "w0" :swarm-id "blocked-before" :harness :codex :max-resumes 100
                    :max-cycles 10 :prompts []
                    :reviewers [{:harness :claude :model "opus-review" :reasoning "high"}]
                    :task-execution {:wall_time_seconds 30 :attempt_timeout_seconds 1
                                     :prepare_only_task_ids ["one"] :validators {"two" gate}}}
            create-workspace! (fn [cycle]
                                (let [path (str root "-hold-" cycle) branch (str "hold-" cycle)]
                                  (git root "worktree" "add" "-b" branch path)
                                  (let [ws {:path path :dir branch :name branch :branch branch}]
                                    (swap! workspaces conj ws) ws)))]
        (try
          (System/setProperty "user.dir" root)
          (reset! shutdown false)
          (tasks/configure! backend)
          (when (= :stopped-active mode)
            ;; Exact recovery shape of e992a8d9: attempt 9 was stopped with
            ;; claim revision 4; its owner subsequently held it at revision 5.
            (swap! store assoc "one" (assoc (task "one" "blocked") :revision 5))
            (write-state! "original" "w0"
                          {:execution {:cycle 1 :attempt 9 :claim_revision 4 :task_id "one"
                                       :session_id "01a15311-76ff-7201-a0ab-cd928d984daa"
                                       :deadline_ms (+ (execution/now-ms) 20000) :accepted_count 0
                                       :workspace (create-workspace! 1) :phase "implementing"
                                       :validator_digest nil
                                       :feedback {:reason "model-attempt-failed" :exit 143}}}))
          (binding [buddy/*transport* (fixture-transport root store calls gate)]
            (with-redefs [worker/build-template-tokens (fn [& _] {})
                          worker/create-iteration-worktree! (fn [_ _ _ cycle] (create-workspace! cycle))
                          cambium/list-workspaces (fn [_] @workspaces)
                          cambium/claim-existing! (fn [_ ws] ws)
                          cambium/remove! (fn [& _] {:removed? false})
                          runs/write-worker-state!
                          (fn [swarm id state]
                            (write-state! swarm id state)
                            (when (= "waiting" (:status state)) (reset! shutdown true)))
                          harness/run-command!
                          (fn [h opts]
                            (if (= :claude h)
                              (do (swap! review-calls conj opts) {:exit 0 :out "VERDICT: APPROVED" :err ""})
                              (let [force? (and (not= :stopped-active mode) (empty? @model-calls))]
                                (swap! model-calls conj opts)
                                (when force?
                                  (case mode
                                    :canonical-blocked (swap! store update "one" #(-> % (assoc :status "blocked") (update :revision inc)))
                                    :canonical-paused (swap! store update "one" #(-> % (assoc :paused true) (update :revision inc)))
                                    :canonical-review (swap! store update "one" #(-> % (assoc :status "review") (update :revision inc)))
                                    :canonical-revision (swap! store update-in ["one" :revision] inc)
                                    nil))
                                (when (and (not force?) (not @reopening?))
                                  (commit-file! (:cwd opts) "two.txt" "repaired\n"))
                                (when @reopening? (reset! shutdown true))
                                {:exit 0 :err ""
                                 :out (str (json/generate-string {:type "session.started" :sessionId (if (or force? @reopening?) "force-session" "independent-session")}) "\n"
                                           (json/generate-string {:type "text.delta"
                                                                 :text (cond @reopening? "Working on the explicitly reopened task"
                                                                             (and force? (= :structured mode))
                                                                             (str force-blocked-output "\nCOMPLETE_AND_READY_FOR_MERGE")
                                                                             :else "COMPLETE_AND_READY_FOR_MERGE")}))})))]
              (let [result (worker/run-worker! (cond-> config (= :stopped-active mode) (assoc :resume-swarm-id "original")))
                    saved (:execution (runs/read-worker-state "blocked-before" "w0"))
                    held (get-in saved [:held_executions :one])
                    expected-session (if (= :stopped-active mode) "01a15311-76ff-7201-a0ab-cd928d984daa" "force-session")]
                (is (= :interrupted (:status result)))
                (is (= "done" (get-in @store ["two" :status])))
                (is (= 1 (:completed result)))
                (is (= (if (= :stopped-active mode) 1 2) (count @model-calls)))
                (is (= 1 (count @review-calls)))
                (is (= 1 (count (filter #(= "oompa" (first %)) @calls))))
                (is (= expected-session (:session_id held)))
                (is (= (if (= :stopped-active mode) 9 1) (:attempt held)))
                (is (.isDirectory (io/file (get-in held [:workspace :path]))))
                (when (= :structured mode)
                  (is (= "model-blocked" (:held_reason held)))
                  (is (= "blocked" (get-in @store ["one" :status])))
                  (is (= (:reason (execution/blocked-result force-blocked-output "one"))
                         (get-in @store ["one" :blockedReason])))
                  (is (str/includes? (get-in @store ["one" :nextAction]) "CUDA"))
                  (is (= 1 (count (:attempt_history held))))
                  (is (str/includes? (get-in held [:attempt_history 0 :output]) "CUDA")))
                (when (= :stopped-active mode)
                  (is (= (:deadline_ms saved) (get-in (runs/read-worker-state "original" "w0") [:execution :deadline_ms])))
                  (is (= 4 (:claim_revision held)))
                  (is (= 5 (:held_task_revision held))))
                ;; Restart cannot retry unchanged held work, even when its
                ;; canonical state remains in_progress with a newer revision.
                (reset! shutdown false)
                (tasks/configure! backend)
                (let [restarted (worker/run-worker! (assoc config :swarm-id "blocked-after" :resume-swarm-id "blocked-before"))
                      after (:execution (runs/read-worker-state "blocked-after" "w0"))]
                  (is (= :interrupted (:status restarted)))
                  (is (= saved after))
                  (is (= (if (= :stopped-active mode) 1 2) (count @model-calls)))
                  (is (= 1 (count @review-calls))))
                (when (= :structured mode)
                  ;; Metadata edits while blocked do not reopen the task.
                  (swap! store update "one" #(-> % (assoc :nextAction "Owner is investigating") (update :revision inc)))
                  (reset! shutdown false)
                  (tasks/configure! backend)
                  (worker/run-worker! (assoc config :swarm-id "metadata-only" :resume-swarm-id "blocked-after"))
                  (is (= 2 (count @model-calls)))
                  ;; Only an explicit open at a new revision resumes the
                  ;; retained workspace/session; stopping here avoids any gate.
                  (swap! store update "one" #(-> % (assoc :status "open") (update :revision inc)))
                  (reset! reopening? true)
                  (reset! shutdown false)
                  (tasks/configure! backend)
                  (worker/run-worker! (assoc config :swarm-id "reopened" :resume-swarm-id "metadata-only"))
                  (let [resumed (:execution (runs/read-worker-state "reopened" "w0"))]
                    (is (= 3 (count @model-calls)))
                    (is (= (:workspace held) (:workspace resumed)))
                    (is (= "force-session" (:session-id (last @model-calls))))
                    (is (:resume? (last @model-calls)))
                    (is (= (:deadline_ms saved) (:deadline_ms resumed)))
                    (is (= 2 (:attempt resumed)))
                    (is (= 2 (count (:attempt_history resumed)))))))))
          (finally
            (reset! shutdown false)
            (tasks/configure! nil)
            (System/setProperty "user.dir" previous-root)
            (doseq [workspace @workspaces] (fs/delete-tree (:path workspace)))
            (fs/delete-tree root)))))))

(let [{:keys [fail error]} (run-tests 'agentnet.task-execution-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
