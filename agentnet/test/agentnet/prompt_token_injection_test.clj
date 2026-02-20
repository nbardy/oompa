(ns agentnet.prompt-token-injection-test
  "Regression tests for worker prompt assembly and token replacement.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.prompt-token-injection-test))'"
  (:require [agentnet.harness :as harness]
            [agentnet.worker :as worker]
            [clojure.java.io :as io]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- base-tokens
  "Tokens used in most tokenization tests."
  []
  {:context_header "ctx"
   :TASK_ROOT "tasks"
   :TASKS_ROOT "tasks"
   :mode_hint "propose"
   :targets "src/a.clj"
   :recent_sec "180"
   :pending_tasks "- task-001: Build thing"})

(t/deftest fresh-start-prompt-tokenization-does-not-leak-placeholders
  (let [captured-prompt (atom nil)
        context {:task_status "Pending: 2, In Progress: 1, Complete: 0"
                 :pending_tasks "- task-001: Build thing"}]
    (with-redefs [agentnet.worker/load-prompt
                  (fn [path]
                    (case path
                      "config/prompts/_task_header.md" "Header path {{TASKS_ROOT}} {{TASK_ROOT}}"
                      "custom/prompt.md" "Prompt {context_header} | {mode_hint} | {targets} | {recent_sec} | {pending_tasks} | roots {TASK_ROOT}|{TASKS_ROOT}|{TASK_ROOT}|{TASKS_ROOT}"
                      nil))
                  agentnet.worker/build-template-tokens
                  (fn
                    ([_] (base-tokens))
                    ([_ _] (base-tokens)))
                  harness/make-session-id (fn [_] "sid-1")
                  harness/build-cmd (fn [_ opts]
                                      (reset! captured-prompt (:prompt opts))
                                      ["echo" "ok"])
                  harness/process-stdin (fn [& _] :close)
                  process/sh (fn [& _] {:exit 0 :out "WORKING" :err ""})
                  harness/parse-output (fn [_ out session-id]
                                         {:output out :session-id session-id})]
      (worker/run-agent! {:id "w0"
                         :swarm-id "sw-1"
                         :harness :codex
                         :model "gpt-5"
                         :prompts ["custom/prompt.md"]}
                        "/tmp/oompa-test-worktree"
                        context
                        nil
                        false)
      (let [prompt @captured-prompt]
        (t/is (string? prompt))
        (t/is (str/starts-with? prompt "[oompa:sw-1:w0] "))
        (t/is (str/includes? prompt "Header path tasks tasks"))
      (t/is (str/includes? prompt "Prompt ctx | propose | src/a.clj | 180 | - task-001: Build thing | roots tasks|tasks|tasks|tasks"))
      (doseq [token ["{context_header}" "{mode_hint}" "{targets}" "{recent_sec}" "{pending_tasks}"
                     "{TASK_ROOT}" "{TASKS_ROOT}" "{{TASK_ROOT}}" "{{TASKS_ROOT}}"]]
          (t/is (not (str/includes? prompt token))
                (str "placeholder leaked into prompt: " token)))))))

(t/deftest resume-prompt-override-takes-precedence
  (let [captured-prompt (atom nil)
        override "## Claim Results\nClaimed: task-001"
        context {:task_status "Pending: 1, In Progress: 1, Complete: 0"
                 :pending_tasks "- task-002: Refactor"}]
    (with-redefs [agentnet.worker/load-prompt (fn [_] "SHOULD NOT BE USED")
                  agentnet.worker/build-template-tokens
                  (fn
                    ([_] {:context_header "SHOULD_NOT_APPEAR"})
                    ([_ _] {:context_header "SHOULD_NOT_APPEAR"}))
                  harness/make-session-id (fn [_] "sid-2")
                  harness/build-cmd (fn [_ opts]
                                      (reset! captured-prompt (:prompt opts))
                                      ["echo" "ok"])
                  harness/process-stdin (fn [& _] :close)
                  process/sh (fn [& _] {:exit 0 :out "WORKING" :err ""})
                  harness/parse-output (fn [_ out session-id]
                                         {:output out :session-id session-id})]
      (worker/run-agent! {:id "w0"
                         :swarm-id "sw-2"
                         :harness :codex
                         :model "gpt-5"
                         :prompts ["custom/prompt.md"]}
                        "/tmp/oompa-test-worktree"
                        context
                        "sid-2"
                        true
                        :resume-prompt-override override)
      (let [prompt @captured-prompt]
        (t/is (str/starts-with? prompt "[oompa:sw-2:w0] "))
        (t/is (str/includes? prompt override))
        (t/is (not (str/includes? prompt "Continue working. Signal COMPLETE_AND_READY_FOR_MERGE")))
      (t/is (not (str/includes? prompt "SHOULD NOT BE USED")))
        (t/is (not (str/includes? prompt "SHOULD_NOT_APPEAR")))))))

(t/deftest resume-prompt-override-injects-task-root
  (let [captured-prompt (atom nil)
        override "## Claim Results\nClaimed: task-001\nRoots: {TASK_ROOT}|{{TASK_ROOT}}|{TASKS_ROOT}|{{TASKS_ROOT}}"
        context {:task_status "Pending: 1, In Progress: 1, Complete: 0"
                 :pending_tasks "- task-002: Refactor"}
        parent (io/file "/tmp" (str "oompa-include-directive-root-" (System/currentTimeMillis)))
        worktree-path (.getPath (io/file parent "child"))]
    (.mkdirs (io/file parent "tasks"))
    (.mkdirs (io/file worktree-path))
    (with-redefs [agentnet.worker/task-root-for-cwd (fn [_] "../tasks")
                  agentnet.worker/build-template-tokens
                  (fn
                    ([_] {:TASK_ROOT "../tasks" :TASKS_ROOT "../tasks"})
                    ([_ _] {:TASK_ROOT "../tasks" :TASKS_ROOT "../tasks"}))
                  harness/make-session-id (fn [_] "sid-3")
                  harness/build-cmd (fn [_ opts]
                                      (reset! captured-prompt (:prompt opts))
                                      ["echo" "ok"])
                  harness/process-stdin (fn [& _] :close)
                  process/sh (fn [& _] {:exit 0 :out "WORKING" :err ""})
                  harness/parse-output (fn [_ out session-id]
                                         {:output out :session-id session-id})]
      (worker/run-agent! {:id "w0"
                         :swarm-id "sw-3"
                         :harness :codex
                         :model "gpt-5"
                         :prompts ["custom/prompt.md"]}
                        worktree-path
                        context
                        "sid-3"
                        true
                        :resume-prompt-override override)
      (let [prompt @captured-prompt
            placeholders ["{TASK_ROOT}" "{TASKS_ROOT}" "{{TASK_ROOT}}" "{{TASKS_ROOT}}"]]
        (t/is (str/starts-with? prompt "[oompa:sw-3:w0] "))
        (t/is (str/includes? prompt "Roots: ../tasks|../tasks|../tasks|../tasks"))
        (doseq [token placeholders]
          (t/is (not (str/includes? prompt token))
                (str "placeholder leaked into resume override: " token)))))))

(t/deftest include-directive-expands-shared-file
  (let [tmpdir (io/file "/tmp" (str "oompa-include-directive-" (System/currentTimeMillis)))
        shared (io/file tmpdir "shared.md")
        role (io/file tmpdir "role.md")]
    (.mkdirs tmpdir)
    (spit shared "Shared context line.\n")
    (spit role (str "Role prompt header.\n"
                     "#oompa_directive:include_file \"shared.md\"\n"
                     "Role prompt footer.\n"))
    (let [loaded (agentnet.agent/load-custom-prompt (.getPath role))
          shared-content (slurp shared)]
      (t/is (str/includes? loaded "Role prompt header."))
      (t/is (str/includes? loaded shared-content))
      (t/is (str/includes? loaded "We have included the content of file: \"shared.md\" below"))
      (t/is (not (str/includes? loaded "#oompa_directive:include_file"))))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.prompt-token-injection-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "prompt token injection tests failed"
                      {:fail fail :error error})))))

;; Auto-run when loaded
(run-tests!)
