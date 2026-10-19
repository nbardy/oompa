(ns agentnet.cambium-test
  (:require [agentnet.cambium :as cambium]
            [agentnet.cli :as cli]
            [cheshire.core :as json]
            [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [agentnet.agent :as agent]
            [agentnet.worktree :as worktree]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]))

(def root "/tmp/oompa-lifecycle-test")
(def workspace {:name "oompa-test" :dir "oompa-test" :branch "oompa/test"
                :path (cambium/workspace-path root "oompa-test")})

(defn shell-stub [calls overrides]
  (fn [cmd opts]
    (swap! calls conj [cmd opts])
    (merge {:exit 0 :out "" :err ""}
           (cond
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
        (is (= ["git" "cambium" "add" "-b"] (subvec cmd 0 4)))
        (is (= ["-name" (:name a) "-ephemeral" (:path a)] (subvec cmd 5)))
        (is (= 2 (count @calls)))))))

(deftest creation-failure-never-falls-back-or-removes
  (let [calls (atom [])]
    (with-redefs [process/sh (fn [cmd opts] (swap! calls conj [cmd opts])
                              {:exit 1 :err "Cambium unavailable"})
                  cambium/mark-owner! (fn [& _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cambium creation failed"
                           (cambium/create! root "worker" "oompa/")))
      (is (= 1 (count @calls)))
      (is (= ["git" "cambium" "add"] (take 3 (ffirst @calls)))))))

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
