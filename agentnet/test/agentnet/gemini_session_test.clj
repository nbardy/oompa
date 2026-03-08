(ns agentnet.gemini-session-test
  "Regression tests for Gemini session capture/resume behavior.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.gemini-session-test))'"
  (:require [agentnet.harness :as harness]
            [clojure.test :as t]))

(t/deftest parse-output-captures-normalized-session-started
  (let [raw (str "{\"type\":\"session.started\",\"sessionId\":\"sid-normalized\"}\n"
                 "{\"type\":\"text.delta\",\"text\":\"hello\"}\n")
        parsed (harness/parse-output :gemini2 raw nil)]
    (t/is (= "sid-normalized" (:session-id parsed)))
    (t/is (= "hello" (:output parsed)))))

(t/deftest parse-output-captures-gemini-raw-init-session-id
  (let [raw (str "{\"type\":\"init\",\"session_id\":\"sid-gemini\"}\n"
                 "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"hello\",\"delta\":true}\n")
        parsed (harness/parse-output :gemini2 raw nil)]
    (t/is (= "sid-gemini" (:session-id parsed)))))

(t/deftest gemini-session-strategy-is-extracted
  (t/is (nil? (harness/make-session-id :gemini2))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'agentnet.gemini-session-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "gemini session tests failed"
                      {:fail fail :error error})))))

(run-tests!)
