(ns agentnet.cambium-test
  (:require [agentnet.cambium :as cambium]
            [agentnet.cli :as cli]
            [cheshire.core :as json]
            [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [agentnet.bounded-process :as bounded]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]))

(def root "/tmp/oompa-lifecycle-test")
(def source (apply str (repeat 40 "a")))
(def workspace {:name "oompa-test" :dir "oompa-test" :branch "oompa/test"
                :path (cambium/workspace-path root "oompa-test")})

(defn shell-stub [calls overrides]
  (fn [cmd opts]
    (swap! calls conj [cmd opts])
    (merge {:exit 0 :out "" :err ""}
           (cond
             (and (= ["git" "rev-parse"] (take 2 cmd))
                  (#{"HEAD" "HEAD^{commit}"} (last cmd))) {:out (str source "\n")}
             (= ["git" "symbolic-ref" "--short" "HEAD"] cmd) {:out "oompa/test\n"}
             (= ["git" "rev-parse" "--git-path"] (take 3 cmd))
             {:out (str "/tmp/oompa-nonexistent-marker-" (last cmd))})
           (get overrides cmd))))

(deftest creation-is-strict-external-and-unique
  (let [calls (atom [])]
    (with-redefs [process/sh (shell-stub calls {})
                  cambium/mark-owner! (fn [& _] nil)]
      (let [a (cambium/create! root "worker-1" "oompa/")
            b (cambium/create! root "worker-1" "oompa/")
            cmd (ffirst @calls)]
        (is (not= (:path a) (:path b)))
        (is (not (.startsWith (.toPath (io/file (:path a))) (.toPath (io/file root)))))
        (is (= ["git" "rev-parse" "--verify" "HEAD^{commit}"] cmd))
        (is (= ["-name" (:name a) "-ephemeral" (:path a) source] (subvec (first (second @calls)) 5)))
        (is (= source (:source_commit a)))
        (is (= 6 (count @calls)))))))

(deftest linked-root-head-is-explicit-even-when-primary-main-differs
  (let [parent (str (fs/create-temp-dir {:prefix "oompa-source-"}))
        primary (str (io/file parent "primary")) linked (str (io/file parent "linked"))
        actual-sh process/sh commands (atom [])
        git! (fn [cwd & args]
               (let [result (actual-sh (into ["git"] args) {:dir cwd :out :string :err :string})]
                 (when-not (zero? (:exit result)) (throw (ex-info "Fixture git failed" result)))
                 (clojure.string/trim (:out result))))]
    (try
      (.mkdirs (io/file primary))
      (git! primary "init" "-b" "main")
      (git! primary "config" "user.name" "Fixture")
      (git! primary "config" "user.email" "fixture@example.invalid")
      (spit (io/file primary "source.txt") "primary")
      (git! primary "add" ".") (git! primary "commit" "-m" "primary")
      (git! primary "worktree" "add" "-b" "retained" linked)
      (spit (io/file linked "source.txt") "retained")
      (git! linked "add" ".") (git! linked "commit" "-m" "retained")
      (let [expected (git! linked "rev-parse" "HEAD")]
        (is (not= expected (git! primary "rev-parse" "HEAD")))
        (with-redefs [process/sh
                      (fn [argv opts]
                        (swap! commands conj argv)
                        (if (= ["git" "cambium" "add"] (take 3 argv))
                          (do (fs/copy-tree linked (nth argv 8)) {:exit 0 :out "" :err ""})
                          (actual-sh argv opts)))
                      cambium/mark-owner! (fn [& _] nil)
                      cambium/workspace-path (fn [_ name] (str (io/file parent name)))]
          (let [created (cambium/create! linked "worker" "oompa/")
                add (first (filter #(= ["git" "cambium" "add"] (take 3 %)) @commands))]
            (is (= expected (last add)))
            (is (= expected (:source_commit created)))
            (is (= expected (git! (:path created) "rev-parse" "HEAD"))))))
      (finally (fs/delete-tree parent)))))

(deftest unexpected-created-head-refuses-before-model-with-retained-path
  (let [calls (atom [])]
    (with-redefs [process/sh (shell-stub calls {["git" "rev-parse" "HEAD"]
                                              {:out (apply str (repeat 40 "b"))}})
                  cambium/mark-owner! (fn [& _] nil)]
      (let [failure (try (cambium/create! root "worker" "oompa/")
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (clojure.string/includes? (.getMessage failure) "unexpected source"))
        (is (= source (:source_commit (ex-data failure))))
        (is (:path (ex-data failure)))
        (is (= 3 (count @calls)))))))

(deftest creation-failure-never-falls-back-or-removes
  (let [calls (atom [])]
    (with-redefs [process/sh (fn [cmd opts] (swap! calls conj [cmd opts])
                              (if (= "rev-parse" (second cmd))
                                {:exit 0 :out source}
                                {:exit 1 :err "Cambium unavailable"}))
                  cambium/mark-owner! (fn [& _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cambium creation failed"
                           (cambium/create! root "worker" "oompa/")))
      (is (= 2 (count @calls)))
      (is (= ["git" "cambium" "add"] (take 3 (first (second @calls))))))))

(deftest creation-budget-is-scoped-and-clipped-to-original-deadline
  (let [calls (atom [])
        run (fn [argv opts timeout]
              (swap! calls conj {:argv argv :timeout timeout})
              {:exit 0 :out (str source "\n") :err ""})]
    (with-redefs [bounded/run! run cambium/mark-owner! (fn [& _] nil)]
      (binding [cambium/*deadline-ms* (+ (System/currentTimeMillis) 720000)]
        (cambium/create! root "worker" "oompa/")
        (cambium/project-root))
      (is (= [30000 600000 30000 30000] (mapv :timeout @calls)))
      (reset! calls [])
      (binding [cambium/*deadline-ms* (+ (System/currentTimeMillis) 10000)]
        (cambium/create! root "worker" "oompa/"))
      (is (= 3 (count @calls)))
      (is (every? #(< 0 (:timeout %) 10001) @calls))
      (reset! calls [])
      (binding [cambium/*deadline-ms* (dec (System/currentTimeMillis))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot resolve exact Cambium starting source"
                             (cambium/create! root "worker" "oompa/"))))
      (is (empty? @calls)))))

(deftest creation-timeout-retains-partial-custody-without-retry
  (let [marks (atom []) calls (atom [])]
    (with-redefs [cambium/mark-owner! (fn [& args] (swap! marks conj args))
                  bounded/run! (fn [argv _ timeout]
                                 (swap! calls conj [argv timeout])
                                 (if (= "rev-parse" (second argv))
                                   {:exit 0 :out source}
                                   {:exit 124 :out "" :err "partial dependency clone timed out"
                                    :timed-out? true :duration-ms timeout}))]
      (binding [cambium/*deadline-ms* (+ (System/currentTimeMillis) 720000)]
        (let [failure (try (cambium/create! root "worker" "oompa/")
                           (catch clojure.lang.ExceptionInfo error error))
              data (ex-data failure)]
          (is (instance? clojure.lang.ExceptionInfo failure))
          (is (= 1 (count @marks)))
          (is (= 2 (count @calls)))
          (is (= ["git" "cambium" "add"] (take 3 (first (second @calls)))))
          (is (= 600000 (second (second @calls))))
          (is (true? (get-in data [:result :timed-out?])))
          (is (= (cambium/workspace-path root (:dir data)) (:path data)))
          (is (= (:dir data) (second (first @marks)))))))))

(deftest cleanup-only-removes-clean-integrated-inactive-work
  (doseq [[label additions overrides expected]
          [[:clean {} {} nil]
           [:active {:current-task "task-1"} {} :active]
           [:dirty {} {["git" "status" "--porcelain"] {:out "?? result.txt\n"}} :dirty]
           [:unmerged {} {["git" "merge-base" "--is-ancestor" "oompa/test" "HEAD"] {:exit 1}} :unmerged]
           [:unknown-status {} {["git" "status" "--porcelain"] {:exit 128}} :unreadable]
           [:changed-branch {} {["git" "symbolic-ref" "--short" "HEAD"] {:out "other"}} :branch-changed]
           [:legacy {:path "/tmp/legacy"} {} :unmanaged]]]
    (testing (name label)
      (let [calls (atom [])]
        (with-redefs [process/sh (shell-stub calls overrides)]
          (let [result (cambium/remove! root (merge workspace additions))
                removals (filter #(= "cambium" (ffirst %)) @calls)]
            (is (= (nil? expected) (:removed? result)))
            (is (= expected (:reason result)))
            (is (= (if expected 0 1) (count removals)))
            (when-not expected
              (is (= ["cambium" "remove" "oompa-test"] (ffirst removals))))))))))

(deftest interrupted-merge-is-preserved
  (let [calls (atom [])
        marker (java.io.File/createTempFile "oompa-merge-head-" ".marker")]
    (try
      (with-redefs [process/sh (shell-stub calls {["git" "rev-parse" "--git-path" "MERGE_HEAD"]
                                                {:out (.getPath marker)}})]
        (is (= :in-progress (:reason (cambium/remove! root workspace))))
        (is (not-any? #(= "cambium" (ffirst %)) @calls)))
      (finally (.delete marker)))))

(deftest cleanup-failure-is-reported-without-destructive-retry
  (let [calls (atom [])]
    (with-redefs [process/sh (shell-stub calls {["cambium" "remove" "oompa-test"] {:exit 1 :err "busy"}})]
      (is (= :remove-failed (:reason (cambium/remove! root workspace))))
      (is (= 1 (count (filter #(= "cambium" (ffirst %)) @calls)))))))

(deftest worker-lifecycle-uses-shared-helper
  (let [calls (atom [])]
    (with-redefs [cambium/create! (fn [& args] (swap! calls conj args) workspace)
                  cambium/remove! (fn [& args] (swap! calls conj args) {:removed? true})]
      (is (= workspace (worker/create-iteration-worktree! root "swarm" "w0" 1)))
      (is (= {:removed? true} (worker/cleanup-worktree! root (:dir workspace) (:branch workspace))))
      (is (= [root "sswarm-w0-i1" "oompa/"] (first @calls)))
      (is (= (:path workspace) (:path (second (second @calls))))))))

(deftest pool-release-preserves-dirty-work-and-agent-errors
  (let [calls (atom [])
        busy (assoc workspace :id "w0" :status :busy :current-task "task-1")]
    (with-redefs [process/sh (shell-stub calls {["git" "-C" (:path workspace) "status" "--porcelain"]
                                              {:out " M source.clj"}})
                  worktree/save-state! identity]
      (is (= :dirty (:status (first (worktree/release! [busy] "w0" {:reset? true})))))
      (is (not-any? #(some #{"reset" "clean" "-D" "--force"} (first %)) @calls))
      (with-redefs [worktree/acquire! (fn [pool _] [pool busy])]
        (is (thrown-with-msg? Exception #"agent failed"
                             (worktree/with-worktree [busy] "task-1"
                               (fn [_] (throw (Exception. "agent failed"))))))))))

(deftest cleanup-discovers-external-workspaces-and-preserves-unregistered-state
  (let [removed (atom [])]
    (with-redefs [cambium/project-root (constantly root)
                  cambium/list-workspaces (constantly [workspace (assoc workspace :name "pool" :branch "work/pool")])
                  cambium/remove! (fn [_ w] (swap! removed conj w) {:removed? true})]
      (is (= {:dirs-removed 1 :dirs-preserved 0 :branches-removed 0}
             (cli/cleanup-iteration-worktrees!)))
      (is (= [workspace] @removed)))))

(deftest external-workspace-discovery-is-repository-scoped
  (let [calls (atom [])
        other (assoc workspace :name "oompa-other" :repository_root "/tmp/other" :ephemeral true)
        ours (assoc workspace :repository_root root :ephemeral true)]
    (with-redefs [process/sh (shell-stub calls {["git" "cambium" "list" "-json"]
                                              {:out (json/generate-string [ours other (assoc ours :ephemeral false)])}})]
      (is (= [(:name workspace)] (mapv :name (cambium/list-workspaces root)))))))

(deftest cleanup-does-not-touch-a-live-foreign-owner
  (let [calls (atom [])]
    (with-redefs [cambium/active-owner? (fn [& _] true)
                  process/sh (shell-stub calls {})]
      (is (= :active-owner (:reason (cambium/remove! root workspace))))
      (is (empty? @calls)))))

(deftest salvage-recognizes-new-external-names
  (is (= {:swarm-token "abcd1234" :worker-id "w0" :iteration 2}
         (cli/parse-worktree-name "/tmp/.oompa-worktrees/repo/oompa-sabcd1234-w0-i2-12345678-1234-1234-1234-123456789abc"))))

(deftest merge-complete-requires-git-proof
  (doseq [integrated? [true false]]
    (let [calls (atom [])]
      (with-redefs [process/sh (fn [cmd opts]
                                (swap! calls conj cmd)
                                (cond
                                  (= ["git" "symbolic-ref" "--short" "HEAD"] cmd)
                                  {:exit 0 :out (if (= (:dir opts) root) "main" "oompa/test")}
                                  (= ["git" "merge-base" "--is-ancestor" "oompa/test" "HEAD"] cmd)
                                  {:exit (if integrated? 0 1) :out ""}
                                  :else {:exit 0 :out ""}))
                    worker/build-merge-prompt (fn [& _] "merge")
                    harness/run-command! (fn [& _] {:exit 0 :out "MERGE_COMPLETE abc123"})
                    harness/parse-output (fn [& _] {:output "MERGE_COMPLETE abc123"})
                    agent/parse-merge-complete-signal (constantly "abc123")
                    agent/needs-followup-signal? (constantly false)]
        (is (= integrated? (:ok? (worker/run-merge-agent! {:harness :codex} (:path workspace)
                                                                         (:branch workspace) root "session" "w0"))))
        (is (some #{["git" "merge-base" "--is-ancestor" "oompa/test" "HEAD"]} @calls))))))

(run-tests)
