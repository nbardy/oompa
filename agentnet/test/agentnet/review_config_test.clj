(ns agentnet.review-config-test
  "Regression tests for reviewer config precedence.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.review-config-test))'"
  (:require [agentnet.cli]
            [clojure.test :as t]))

(t/deftest top-level-needs-review-false-disables-generic-reviewers
  (t/is (= []
           (#'agentnet.cli/parse-reviewers-config
            {:needs_review false
             :review_model "codex:gpt-5.4"}))))

(t/deftest worker-inherits-generic-reviewers-by-default
  (let [generic (#'agentnet.cli/parse-reviewers-config
                 {:review_model "codex:gpt-5.4"})
        reviewers (#'agentnet.cli/resolve-worker-reviewers
                   {:model "gemini:gemini-3.1-pro-preview"}
                   generic)]
    (t/is (= [{:harness :codex :model "gpt-5.4"}]
             reviewers))))

(t/deftest worker-needs-review-false-overrides-inherited-reviewers
  (let [generic (#'agentnet.cli/parse-reviewers-config
                 {:review_model "codex:gpt-5.4"})
        reviewers (#'agentnet.cli/resolve-worker-reviewers
                   {:model "gemini:gemini-3.1-pro-preview"
                    :needs_review false
                    :review_model "gemini:gemini-3.1-pro-preview"}
                   generic)]
    (t/is (= [] reviewers))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.review-config-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "review config tests failed"
                      {:fail fail :error error})))))

(run-tests!)
