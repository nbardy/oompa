(ns agentnet.reviewer-effort-test
  (:require [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [agentnet.cli :as cli]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :as t]))

(t/deftest reviewer-config-retains-requested-effort
  (t/is (= [{:harness :claude :model "claude-opus-5-5" :reasoning "high" :prompts []}]
           (#'cli/parse-reviewers-config {:reviewers [{:model "claude:claude-opus-5-5:high"}]}))))

(t/deftest review-dispatch-passes-effort
  (let [seen (atom nil)]
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "" :err ""})
                  harness/run-command! (fn [_ opts] (reset! seen opts)
                                         {:exit 0 :out "VERDICT: APPROVED" :err ""})
                  harness/parse-output (fn [_ out _] {:output out})]
      (#'worker/run-reviewer! {:id "w0" :swarm-id "test"
                               :reviewers [{:harness :claude :model "claude-opus-5-5" :reasoning "high"}]}
                              "/tmp/test-worktree" [])
      (t/is (= "high" (:reasoning @seen)))
      (t/is (= "claude-opus-5-5" (:model @seen))))))

(t/deftest claude-and-codex-execution-map-effort-to-real-runtime-input
  (let [seen (atom nil)]
    (with-redefs [process/sh (fn [_ opts] (reset! seen (json/parse-string (:in opts) true))
                             {:exit 0 :out "" :err ""})]
      (harness/run-command! :claude {:model "claude-opus-5-5" :reasoning "high" :prompt "Review"})
      (t/is (= ["--effort" "high"] (:extraArgs @seen)))
      (t/is (not (contains? @seen :reasoningEffort)))
      (harness/run-command! :codex {:model "gpt-6.1-sol" :reasoning "xhigh" :prompt "Implement"})
      (t/is (= "xhigh" (:reasoningEffort @seen))))))

(let [{:keys [fail error]} (t/run-tests 'agentnet.reviewer-effort-test)]
  (when (pos? (+ fail error)) (throw (ex-info "Reviewer effort tests failed" {}))))
