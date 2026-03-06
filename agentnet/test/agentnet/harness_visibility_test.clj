(ns agentnet.harness-visibility-test
  "Regression tests for surfacing agent-cli stderr/debug context in worker results.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.harness-visibility-test))'"
  (:require [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [clojure.string :as str]
            [clojure.test :as t]))

(t/deftest run-agent-carries-agent-cli-stderr-snippet
  (with-redefs [agentnet.worker/load-prompt (fn [_] "worker prompt")
                agentnet.worker/build-template-tokens
                (fn
                  ([_] {:context_header "ctx"})
                  ([_ _] {:context_header "ctx"}))
                harness/make-session-id (fn [_] "sid-1")
                harness/run-command! (fn [_ _]
                                       {:exit 3
                                        :out "{\"type\":\"error\",\"message\":\"Gemini emitted unrecognized event type \\\"tool_result\\\"\"}\n{\"type\":\"turn.complete\",\"reason\":\"error\"}\n"
                                        :err "[agent-cli raw gemini2 stdout] {\"type\":\"tool_result\",\"status\":\"success\"}\n"})
                harness/parse-output (fn [_ out session-id]
                                       {:output "Gemini emitted unrecognized event type \"tool_result\""
                                        :session-id session-id
                                        :warning "agent-cli reported error events: Gemini emitted unrecognized event type \"tool_result\""
                                        :raw-snippet out})]
    (let [result (worker/run-agent! {:id "w0"
                                     :swarm-id "sw-1"
                                     :harness :gemini2
                                     :model "gemini-3.1-pro-preview"
                                     :prompts []}
                                    "/tmp/oompa-test-worktree"
                                    {:task_status "Pending: 1" :pending_tasks "- task-001"}
                                    nil
                                    false)]
      (t/is (= 3 (:exit result)))
      (t/is (str/includes? (:output result) "tool_result"))
      (t/is (str/includes? (:stderr-snippet result) "[agent-cli raw gemini2 stdout]")))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.harness-visibility-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "harness visibility tests failed"
                      {:fail fail :error error})))))

(run-tests!)
