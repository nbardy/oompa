(ns agentnet.abandoned-claim-test
  "Regressions for swarm 455987ea (2026-10-24), which went idle with work left:
   1. a worker that gave up on a claim (attempt budget after reviewer
      NEEDS_CHANGES, timeout, run exit) left the Buddy task in_progress, and no
      other worker may take an in_progress task, so the queue drained;
   2. a worker's `blocked` return was never checked by anyone.
   Real Git workspaces, a real command oracle and an in-memory Buddy store with
   CAS revisions; only the model and reviewer processes are stubbed.

   Run: bb -cp agentnet/src:agentnet/test agentnet/test/agentnet/abandoned_claim_test.clj"
  (:require [agentnet.task-execution :as execution]
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

(defn- git [root & args]
  (let [result (process/sh (into ["git"] args) {:dir (str root) :out :string :err :string})]
    (when-not (zero? (:exit result)) (throw (ex-info "Fixture git failed" result)))
    (str/trim (:out result))))

(def ^:private oracle
  (str "import json, os, pathlib\n"
       "task = os.environ['OOMPA_TASK_ID']\n"
       "actual = pathlib.Path(task + '.txt').read_text().strip()\n"
       "accepted = actual == 'repaired'\n"
       "print(json.dumps({'schema':'oompa.task-validation/v1', 'task_id':task, "
       "'source_commit':os.environ['OOMPA_SOURCE_COMMIT'], 'accepted':accepted, 'actual':actual}))\n"
       "raise SystemExit(0 if accepted else 1)\n"))

(defn- fixture-repo []
  (let [root (str (fs/create-temp-dir {:prefix "oompa-abandon-"}))]
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

(defn- gate [root]
  {:command ["python3" "oracle.py"] :timeout_seconds 5
   :source_files {"oracle.py" (execution/file-sha256 (io/file root "oracle.py"))}})

(defn- commit-file! [root file contents]
  (spit (io/file root file) contents)
  (git root "add" file)
  (git root "commit" "-m" (str "fixture edit " file)))

(def ^:private ceo-line "CEO DECISION 2026-10-24: local scope only; hosted release is out of scope tonight.")

(defn- task [id status]
  {:id id :workspaceId "workspace" :ownerId "buddy" :title id
   :doneCriteria (str id ".txt reads repaired and the oracle accepts it")
   :nextAction ceo-line :status status :paused false :revision 1})

(defn- transport
  "In-memory Buddy host: CAS on every write, real oracle + ff-only merge on accept."
  [root store]
  (fn [tool args]
    (case tool
      "tasks" (if (= "list" (get-in args [:action :kind]))
                (vec (vals @store))
                {:task (get @store (get-in args [:action :taskId]))})
      "task_write"
      (let [{:keys [taskId baseRevision changes]} (:write args)
            old (get @store taskId)]
        (when-not (= baseRevision (:revision old))
          (throw (ex-info "revision conflict" {:type :revision-conflict})))
        (let [updated (merge old changes {:revision (inc baseRevision)})]
          (swap! store assoc taskId updated)
          updated))
      "oompa"
      (let [{:keys [taskId baseRevision sourceCommit workspacePath]} (:action args)
            old (get @store taskId)
            _ (when-not (= baseRevision (:revision old))
                (throw (ex-info "revision conflict" {:type :revision-conflict})))
            receipt (execution/validate! workspacePath old (gate root) (+ (execution/now-ms) 5000))]
        (if (execution/accepted? receipt)
          (do (git root "merge" "--ff-only" sourceCommit)
              (let [accepted (assoc old :status "done" :revision (inc baseRevision))]
                (swap! store assoc taskId accepted)
                {:kind "accepted" :task accepted :validation receipt}))
          {:kind (:status receipt) :task old :validation receipt})))))

(defn- model-out [session text]
  {:exit 0 :err ""
   :out (str (json/generate-string {:type "session.started" :sessionId session}) "\n"
             (json/generate-string {:type "text.delta" :text text}) "\n")})

(defn- review-out [text]
  {:exit 0 :err "" :out (json/generate-string {:type "text.delta" :text text})})

(defn- worker-config [id swarm root task-ids & {:as overrides}]
  (merge {:id id :swarm-id swarm :harness :codex :model "fixture" :max-resumes 2 :max-cycles 10
          :prompts [] :reviewers [{:harness :claude :model "review-fixture"}]
          :task-execution {:wall_time_seconds 30 :attempt_timeout_seconds 5
                           :validators (into {} (map (fn [id] [id (gate root)])) task-ids)}}
         overrides))

(defmacro ^:private with-fixture
  "Binds root/store/workspaces, configures the Buddy backend and stubs only
   worktree creation/removal and the model/reviewer processes (run-command!)."
  [[root store workspaces task-ids initial] run-command & body]
  `(let [~root (fixture-repo) previous# (System/getProperty "user.dir")
         ~store (atom ~initial) ~workspaces (atom []) counter# (atom 0)
         shutdown# (var-get #'worker/shutdown-requested?)]
     (try
       (System/setProperty "user.dir" ~root)
       (reset! shutdown# false)
       (tasks/configure! {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids ~task-ids})
       (binding [buddy/*transport* (transport ~root ~store)]
         (with-redefs [worker/build-template-tokens (fn [& _#] {})
                       worker/create-iteration-worktree!
                       (fn [& _#]
                         (let [n# (swap! counter# inc)
                               path# (str ~root "-ws-" n#) branch# (str "candidate-" n#)]
                           (git ~root "worktree" "add" "-b" branch# path#)
                           (let [ws# {:path path# :dir branch# :name branch# :branch branch#}]
                             (swap! ~workspaces conj ws#) ws#)))
                       cambium/remove! (fn [& _#] {:removed? false})
                       harness/run-command! ~run-command]
           ~@body))
       (finally
         (reset! shutdown# false)
         (tasks/configure! nil)
         (System/setProperty "user.dir" previous#)
         (doseq [ws# @~workspaces] (fs/delete-tree (:path ws#)))
         (fs/delete-tree ~root)))))

(defn- block-review? [opts] (str/includes? (:prompt opts) "asked the framework to mark the canonical task BLOCKED"))

(deftest reviewer-rejections-until-give-up-requeue-the-task-for-another-worker
  ;; Gap 1. Before the fix "one" stayed in_progress after w0 gave up, so w1
  ;; could not claim it and the swarm stopped with needs-reconciliation.
  (let [reviews (atom [])]
    (with-fixture [root store workspaces ["one"] {"one" (task "one" "open")}]
      (fn [h opts]
        (if (= :claude h)
          (do (swap! reviews conj opts)
              (review-out (if (str/includes? (:prompt opts) ":w0]")
                            "VERDICT: NEEDS_CHANGES\n1. one.txt still lacks the validated repair (fixture finding 7f3a)."
                            "VERDICT: APPROVED")))
          (let [w0? (str/includes? (:prompt opts) ":w0]")]
            (commit-file! (:cwd opts) "one.txt" (if w0? (str "half-done " (count @reviews) "\n") "repaired\n"))
            (model-out (if w0? "w0-session" "w1-session") "COMPLETE_AND_READY_FOR_MERGE"))))
      (let [first-run (worker/run-worker! (worker-config "w0" "give-up" root ["one"]))
            released (get @store "one")
            held (get-in (runs/read-worker-state "give-up" "w0") [:execution :held_executions :one])
            retained (first @workspaces)]
        (is (= 2 (count @reviews)) "two NEEDS_CHANGES rounds exhaust max-resumes 2")
        (is (= "open" (:status released)) "abandoned claim is released, not stranded in_progress")
        (is (str/starts-with? (:nextAction released) ceo-line) "owner decision text survives the release")
        (is (str/includes? (:nextAction released) "[oompa-release]"))
        (is (str/includes? (:nextAction released) "attempt-budget-exhausted"))
        (is (str/includes? (:nextAction released) (:branch retained)))
        (is (str/includes? (:nextAction released) (git (:path retained) "rev-parse" "HEAD")) "names the retained commit")
        (is (str/includes? (:nextAction released) "fixture finding 7f3a") "carries the reviewer's last findings")
        (is (= (:revision released) (:held_task_revision held)) "abandoning worker pins the released revision")
        (is (= "released" (get-in held [:release :outcome])))
        (is (not= :completed (:status first-run)))
        ;; Another worker now takes and finishes the requeued task.
        (let [second-run (worker/run-worker! (worker-config "w1" "pick-up" root ["one"]))]
          (is (= "done" (get-in @store ["one" :status])))
          (is (= 1 (:completed second-run))))))))

(deftest abandoning-worker-does-not-retake-what-it-gave-up
  ;; Without the revision pin a one-worker swarm would loop: claim, hit the
  ;; budget at once (restored attempt count), release, reclaim, forever.
  (let [models (atom 0)]
    (with-fixture [root store workspaces ["one"] {"one" (task "one" "open")}]
      (fn [h opts]
        (if (= :claude h)
          (review-out "VERDICT: NEEDS_CHANGES\n1. still wrong")
          (do (swap! models inc)
              (commit-file! (:cwd opts) "one.txt" (str "try-" @models "\n"))
              (model-out "s" "COMPLETE_AND_READY_FOR_MERGE"))))
      (let [result (worker/run-worker! (worker-config "w0" "no-churn" root ["one"]))]
        (is (= 2 @models))
        (is (= 3 (get-in @store ["one" :revision])) "one claim write + one release write, no reclaim")
        (is (= "open" (get-in @store ["one" :status])))
        (is (= :needs-reconciliation (:status result)))))))

(deftest live-claim-of-another-execution-is-never-released
  (testing "buddy adapter: only this process's own claim at its exact revision"
    (let [store (atom {"one" (assoc (task "one" "in_progress") :revision 4)
                       "two" (task "two" "open")})]
      (try
        (tasks/configure! {:type "buddies" :workspace_id "workspace" :buddy_id "buddy" :task_ids ["one" "two"]})
        (binding [buddy/*transport* (transport "/unused" store)]
          (is (= {:kind :not-owned} (tasks/release-abandoned! (tasks/get-task "one") "note")))
          (is (= 4 (get-in @store ["one" :revision])))
          (is (= "in_progress" (get-in @store ["one" :status])))
          (let [claimed (tasks/claim-task! (tasks/get-task "two"))]
            ;; The owner edits the task after our claim: the CAS refuses.
            (swap! store update "two" #(-> % (assoc :nextAction "owner edit") (update :revision inc)))
            (is (thrown-with-msg? Exception #"changed" (tasks/release-abandoned! claimed "note")))
            (is (= "in_progress" (get-in @store ["two" :status])))))
        (finally (tasks/configure! nil)))))
  (testing "a sibling's exit never touches a task claimed by a live execution"
    (with-fixture [root store workspaces ["one" "two"]
                   {"one" (assoc (task "one" "in_progress") :revision 4) "two" (task "two" "open")}]
      (fn [h opts]
        (if (= :claude h)
          (review-out "VERDICT: APPROVED")
          (do (commit-file! (:cwd opts) "two.txt" "repaired\n")
              (model-out "s" "COMPLETE_AND_READY_FOR_MERGE"))))
      (worker/run-worker! (worker-config "w1" "sibling" root ["one" "two"]))
      (is (= "done" (get-in @store ["two" :status])))
      (is (= "in_progress" (get-in @store ["one" :status])))
      (is (= 4 (get-in @store ["one" :revision]))))))

(def ^:private block-json
  (json/generate-string {:status "blocked" :reason "Hosted release credentials are missing (fixture reason 91c2)."
                         :taskComplete false :readyForMergeValidation false}))

(deftest rejected-block-reopens-the-task-with-the-reviewers-reason
  ;; Gap 2. Before the fix the block was written straight to Buddy and nobody
  ;; ever checked it against the done criteria or the CEO decision.
  (let [block-reviews (atom [])]
    (with-fixture [root store workspaces ["one"] {"one" (task "one" "open")}]
      (fn [h opts]
        (if (= :claude h)
          (do (when (block-review? opts) (swap! block-reviews conj opts))
              (review-out "VERDICT: NEEDS_CHANGES\n1. The CEO DECISION limits scope to local work; hosted credentials are not needed (fixture reason d4e8)."))
          (do (commit-file! (:cwd opts) "one.txt" "partial\n")
              (model-out "s" block-json))))
      (worker/run-worker! (worker-config "w0" "block-rejected" root ["one"] :max-resumes 5))
      (let [reopened (get @store "one")
            prompt (:prompt (first @block-reviews))]
        (is (= 1 (count @block-reviews)))
        (is (str/includes? prompt "fixture reason 91c2") "reviewer sees the worker's blockedReason")
        (is (str/includes? prompt "reads repaired and the oracle accepts it") "reviewer sees the done criteria")
        (is (str/includes? prompt ceo-line) "reviewer sees the current nextAction / CEO decision")
        (is (= "open" (:status reopened)))
        (is (nil? (:blockedReason reopened)) "a rejected block never reaches Buddy")
        (is (str/includes? (:nextAction reopened) "block-rejected"))
        (is (str/includes? (:nextAction reopened) "fixture reason d4e8") "records the reviewer's reason")
        (is (= "rejected" (get-in (runs/read-worker-state "block-rejected" "w0")
                                  [:execution :held_executions :one :block_review])))))))

(deftest confirmed-block-stands
  (with-fixture [root store workspaces ["one"] {"one" (task "one" "open")}]
    (fn [h opts]
      (if (= :claude h)
        (review-out "VERDICT: APPROVED\nThe credentials are outside the repository and not covered by the decision.")
        (do (commit-file! (:cwd opts) "one.txt" "partial\n")
            (model-out "s" block-json))))
    (worker/run-worker! (worker-config "w0" "block-confirmed" root ["one"]))
    (is (= "blocked" (get-in @store ["one" :status])))
    (is (str/includes? (get-in @store ["one" :blockedReason]) "fixture reason 91c2"))
    (is (= ceo-line (get-in @store ["one" :nextAction])) "a confirmed block does not add a release note")))

(deftest wall-time-exit-releases-the-active-claim
  ;; Host-at-exit half of gap 1: the run budget ends mid-task.
  (with-fixture [root store workspaces ["one"] {"one" (task "one" "open")}]
    (fn [h opts]
      (when (not= :claude h) (Thread/sleep 1500))
      (model-out "s" "still working"))
    (let [result (worker/run-worker! (worker-config "w0" "wall-exit" root ["one"]
                                                    :task-execution {:wall_time_seconds 1 :attempt_timeout_seconds 5
                                                                     :validators {"one" (gate root)}}))]
      (is (= :wall-time-exhausted (:status result)))
      (is (= "open" (get-in @store ["one" :status])))
      (is (str/includes? (get-in @store ["one" :nextAction]) "worker-exit-wall-time-exhausted")))))

(deftest release-note-keeps-owner-text-and-replaces-only-its-own-section
  (let [note (fn [prior findings]
               (worker/release-next-action prior {:reason "attempt-timeout" :worker "w0" :swarm "s"
                                                  :attempt 3 :source "branch b at commit c" :findings findings}))
        once (note ceo-line "first findings")
        twice (note once "second findings")]
    (is (str/starts-with? twice (str ceo-line "\n\n[oompa-release]")))
    (is (= 1 (count (re-seq #"\[oompa-release\]" twice))) "repeated requeues do not grow the field")
    (is (str/includes? twice "second findings"))
    (is (not (str/includes? twice "first findings")))
    (is (str/starts-with? (note nil "x") "[oompa-release]"))
    (is (<= (count (note nil (apply str (repeat 5000 "z")))) 2000) "findings are bounded")))

(let [{:keys [fail error]} (run-tests 'agentnet.abandoned-claim-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
